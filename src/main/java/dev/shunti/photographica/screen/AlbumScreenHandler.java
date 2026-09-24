package dev.hitom.photographica.screen;

import dev.hitom.photographica.component.AlbumData;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.item.AlbumItem;
import dev.hitom.photographica.item.PhotoItem;
import dev.hitom.photographica.registry.ModScreenHandlers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The album's own slots — a grid of printed photos, restricted to {@link PhotoItem} the same
 * way a lectern restricts itself to books. The album being edited is always the one in the
 * player's main hand, matching {@code CameraGearScreenHandler}'s reasoning: no extended screen
 * type, no open payload, and putting the album away closes the screen automatically.
 *
 * <p>Contents live in the album's {@link AlbumData} component, not in this handler's own
 * inventory — the {@link SimpleInventory} here is purely the editing surface, loaded from the
 * component on open and written straight back on every change. Same shape as
 * {@code CameraGearScreenHandler}, and for the same reason {@link #onClosed} must not call
 * {@code dropInventory}: these prints belong to the album, not to a scratch workspace.
 */
public class AlbumScreenHandler extends ScreenHandler {

    private final PlayerEntity player;
    private final int capacity;
    private final SimpleInventory inventory;
    private boolean loading = false;

    public AlbumScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ModScreenHandlers.ALBUM, syncId);
        this.player = playerInventory.player;
        this.capacity = AlbumItem.getAlbum(album()).capacity();
        this.inventory = new SimpleInventory(capacity);

        loadFromAlbum();

        int cols = 9;
        int rows = (capacity + cols - 1) / cols;
        for (int i = 0; i < capacity; i++) {
            int row = i / cols;
            int col = i % cols;
            addAlbumSlot(i, 8 + col * 18, 18 + row * 18);
        }

        int invY = 18 + rows * 18 + 14;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, invY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, invY + 58));
        }
    }

    private void addAlbumSlot(int index, int x, int y) {
        addSlot(new Slot(inventory, index, x, y) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return stack.getItem() instanceof PhotoItem;
            }
        });
    }

    private ItemStack album() {
        return player.getStackInHand(Hand.MAIN_HAND);
    }

    private void loadFromAlbum() {
        loading = true;
        AlbumData data = AlbumItem.getAlbum(album());
        List<ItemStack> photos = data.photos();
        for (int i = 0; i < capacity; i++) {
            inventory.setStack(i, i < photos.size() ? photos.get(i).copy() : ItemStack.EMPTY);
        }
        loading = false;
    }

    @Override
    public void onContentChanged(net.minecraft.inventory.Inventory inv) {
        super.onContentChanged(inv);
        if (loading || inv != inventory) return;
        // Server-side only — same reasoning as CameraGearScreenHandler: the client's own copy
        // of the album stack would just be overwritten by the next sync anyway.
        if (!(player instanceof net.minecraft.server.network.ServerPlayerEntity)) return;
        ItemStack album = album();
        if (album.isEmpty()) return;
        List<ItemStack> photos = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) photos.add(inventory.getStack(i).copy());
        album.set(ModDataComponents.ALBUM, new AlbumData(Collections.unmodifiableList(photos), capacity));
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return album().getItem() instanceof AlbumItem;
    }

    /** Slot count the client-side {@code AlbumScreen} lays its grid out for. Exposed rather
     *  than hardcoded on that side because a bigger album tier is a real future possibility. */
    public int albumSlotCount() {
        return capacity;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        // NOT dropInventory() — these prints are the album's own contents, already persisted
        // in its component. See CameraGearScreenHandler#onClosed for the same trap.
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (!slot.hasStack()) return result;

        ItemStack stack = slot.getStack();
        result = stack.copy();

        if (slotIndex < capacity) {
            if (!insertItem(stack, capacity, this.slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!(stack.getItem() instanceof PhotoItem)) return ItemStack.EMPTY;
            if (!insertItem(stack, 0, capacity, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setStack(ItemStack.EMPTY);
        } else {
            slot.markDirty();
        }
        return result;
    }
}
