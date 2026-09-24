package dev.hitom.photographica.screen;

import dev.hitom.photographica.block.entity.FaxMachineBlockEntity;
import dev.hitom.photographica.item.PhotoItem;
import dev.hitom.photographica.registry.ModScreenHandlers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;

public class FaxMachineScreenHandler extends ScreenHandler {

    private final Inventory inventory;
    public final BlockPos pos;
    public final int machineNumber;

    /** Client-side constructor — used by the {@code ExtendedScreenHandlerType} factory, fed
     *  whatever {@link FaxOpenData} the server sent along when it opened this screen. */
    public FaxMachineScreenHandler(int syncId, PlayerInventory playerInventory, FaxOpenData data) {
        this(syncId, playerInventory, new SimpleInventory(2), data.pos(), data.machineNumber());
    }

    /** Server-side constructor. */
    public FaxMachineScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory,
                                   BlockPos pos, int machineNumber) {
        super(ModScreenHandlers.FAX_MACHINE, syncId);
        checkSize(inventory, 2);
        this.inventory = inventory;
        this.pos = pos;
        this.machineNumber = machineNumber;
        inventory.onOpen(playerInventory.player);

        // Slot 0: outgoing photo (x=44, y=35) — only a PhotoItem can be inserted.
        addSlot(new Slot(inventory, FaxMachineBlockEntity.SLOT_OUT, 44, 35) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return stack.getItem() instanceof PhotoItem;
            }
        });

        // Slot 1: received tray (x=80, y=35) — arrivals only; players can take but not insert.
        addSlot(new Slot(inventory, FaxMachineBlockEntity.SLOT_IN, 80, 35) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return false;
            }
        });

        // Player inventory (3 rows × 9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 94 + row * 18));
            }
        }
        // Player hotbar
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 152));
        }
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        this.inventory.onClose(player);
        // NOT dropInventory() — see PrinterScreenHandler#onClosed. Worst case of the four: an
        // incoming fax lands in this machine's in-tray with nobody's screen open, so emptying
        // on close meant merely LOOKING at the machine silently pulled the received photo out
        // of it — and dropped it on the floor if the viewer's inventory happened to be full.
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return inventory.canPlayerUse(player);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (slot.hasStack()) {
            ItemStack stack = slot.getStack();
            result = stack.copy();

            if (slotIndex < 2) {
                if (!insertItem(stack, 2, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (stack.getItem() instanceof PhotoItem) {
                    if (!insertItem(stack, FaxMachineBlockEntity.SLOT_OUT, FaxMachineBlockEntity.SLOT_OUT + 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else {
                    return ItemStack.EMPTY;
                }
            }

            if (stack.isEmpty()) {
                slot.setStack(ItemStack.EMPTY);
            } else {
                slot.markDirty();
            }
        }
        return result;
    }
}
