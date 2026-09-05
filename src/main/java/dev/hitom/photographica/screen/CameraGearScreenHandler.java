package dev.hitom.photographica.screen;

import dev.hitom.photographica.component.CameraGear;
import dev.hitom.photographica.registry.ModScreenHandlers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;

/**
 * The camera body's own slots — lens, film/card, battery and flash in one place, replacing the
 * pile of separate keybindings that used to load and unload each of them.
 *
 * <p>The camera is always the player's main-hand stack rather than something passed in at open
 * time. That keeps this a plain {@link net.minecraft.screen.ScreenHandlerType} (no extended
 * type, no open payload) and matches how every other camera path in the mod resolves the
 * body — {@code CreatePhotoPayload} and friends all read {@code Hand.MAIN_HAND} too.
 *
 * <p>Contents live in the camera's {@link CameraGear} component, not in this inventory: the
 * {@link SimpleInventory} here is just the editing surface, loaded on open and written straight
 * back on every change. That is also why {@link #onClosed} must not call {@code dropInventory} —
 * these stacks belong to the camera, not to a scratch workspace.
 */
public class CameraGearScreenHandler extends ScreenHandler {

    private final PlayerEntity player;
    private final SimpleInventory inventory = new SimpleInventory(CameraGear.SLOT_COUNT);
    /** Guards the write-back while the inventory is being populated from the camera, so
     *  loading the slots doesn't immediately re-serialise them back over the source. */
    private boolean loading = false;

    public CameraGearScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ModScreenHandlers.CAMERA_GEAR, syncId);
        this.player = playerInventory.player;

        loadFromCamera();

        // Gear slots, left to right across the top of the GUI.
        addGearSlot(CameraGear.SLOT_LENS, 44, 20);
        addGearSlot(CameraGear.SLOT_STORAGE, 71, 20);
        addGearSlot(CameraGear.SLOT_BATTERY, 98, 20);
        addGearSlot(CameraGear.SLOT_FLASH, 125, 20);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 51 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 109));
        }
    }

    private void addGearSlot(int index, int x, int y) {
        addSlot(new Slot(inventory, index, x, y) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return CameraGear.accepts(index, stack);
            }

            @Override
            public int getMaxItemCount() {
                return 1;
            }
        });
    }

    private ItemStack camera() {
        return player.getStackInHand(Hand.MAIN_HAND);
    }

    private void loadFromCamera() {
        loading = true;
        CameraGear gear = CameraGear.of(camera());
        for (int i = 0; i < CameraGear.SLOT_COUNT; i++) {
            inventory.setStack(i, gear.get(i).copy());
        }
        loading = false;
    }

    @Override
    public void onContentChanged(net.minecraft.inventory.Inventory inv) {
        super.onContentChanged(inv);
        if (loading || inv != inventory) return;
        // Server-side only: the camera ItemStack the client sees is a copy, so writing there
        // would be discarded on the next inventory sync and briefly show the wrong state.
        // Player type rather than World.isClient — that flipped from field to method at
        // 1.21.11, and getWorld() was renamed the same release.
        if (!(player instanceof net.minecraft.server.network.ServerPlayerEntity)) return;
        ItemStack camera = camera();
        if (camera.isEmpty()) return;
        CameraGear gear = CameraGear.EMPTY;
        for (int i = 0; i < CameraGear.SLOT_COUNT; i++) {
            gear = gear.with(i, inventory.getStack(i).copy());
        }
        // install() rather than a bare component set: it also rebuilds the lens/film/card
        // components every optics and gallery path still reads.
        CameraGear.install(camera, gear);
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        // The body being edited is the one in hand, so putting it away closes the screen —
        // otherwise the slots would keep editing a camera the player is no longer holding.
        return !camera().isEmpty() && dev.hitom.photographica.component.CameraGear.isCamera(camera());
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        // Deliberately NOT dropInventory(): these stacks are the camera's own fittings and are
        // already stored in its component. Emptying them into the player here would duplicate
        // every part on close (see PrinterScreenHandler#onClosed for the same trap).
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (!slot.hasStack()) return result;

        ItemStack stack = slot.getStack();
        result = stack.copy();
        int playerStart = CameraGear.SLOT_COUNT;

        if (slotIndex < playerStart) {
            // Gear -> player inventory.
            if (!insertItem(stack, playerStart, this.slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // Player inventory -> whichever gear slot accepts it, if that slot is free.
            int target = -1;
            for (int i = 0; i < CameraGear.SLOT_COUNT; i++) {
                if (CameraGear.accepts(i, stack) && !this.slots.get(i).hasStack()) {
                    target = i;
                    break;
                }
            }
            if (target < 0) return ItemStack.EMPTY;
            if (!insertItem(stack, target, target + 1, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setStack(ItemStack.EMPTY);
        } else {
            slot.markDirty();
        }
        return result;
    }
}
