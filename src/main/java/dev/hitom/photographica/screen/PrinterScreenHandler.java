package dev.hitom.photographica.screen;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.component.SdCardData;
import dev.hitom.photographica.item.PhotoPaperItem;
import dev.hitom.photographica.item.SdCardItem;
import dev.hitom.photographica.registry.ModItems;
import dev.hitom.photographica.registry.ModScreenHandlers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

public class PrinterScreenHandler extends ScreenHandler {

    private final Inventory inventory;

    /** Client-side constructor. */
    public PrinterScreenHandler(int syncId, PlayerInventory playerInventory) {
        this(syncId, playerInventory, new SimpleInventory(2));
    }

    /** Server-side constructor. */
    public PrinterScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory) {
        super(ModScreenHandlers.PRINTER, syncId);
        checkSize(inventory, 2);
        this.inventory = inventory;
        inventory.onOpen(playerInventory.player);

        // Slot 0: SD Card slot (x=44, y=35)
        addSlot(new Slot(inventory, 0, 44, 35) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return stack.getItem() instanceof SdCardItem;
            }
        });

        // Slot 1: Photo paper slot (x=80, y=35)
        addSlot(new Slot(inventory, 1, 80, 35) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return stack.getItem() instanceof PhotoPaperItem;
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
        dropInventory(player, this.inventory);
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return inventory.canPlayerUse(player);
    }

    @Override
    public boolean onButtonClick(PlayerEntity player, int id) {
        ItemStack sdStack = inventory.getStack(0);
        ItemStack paperStack = inventory.getStack(1);
        if (sdStack.isEmpty() || !(sdStack.getItem() instanceof SdCardItem)) return true;
        SdCardData sdData = sdStack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
        if (sdData.isEmpty()) return true;

        if (id == 0) {
            // Print all
            int count = sdData.photos().size();
            if (paperStack.isEmpty() || !(paperStack.getItem() instanceof PhotoPaperItem)
                    || paperStack.getCount() < count) return true;

            for (PhotoData photoData : sdData.photos()) {
                ItemStack photoStack = new ItemStack(ModItems.PHOTO);
                photoStack.set(ModDataComponents.PHOTO_DATA, photoData);
                if (!player.getInventory().insertStack(photoStack)) {
                    player.dropItem(photoStack, false);
                }
            }
            paperStack.decrement(count);
            sdStack.set(ModDataComponents.SD_CARD, sdData.withoutPhotos());
            inventory.markDirty();
            return true;
        } else if (id == 1) {
            // Print one
            if (paperStack.isEmpty() || !(paperStack.getItem() instanceof PhotoPaperItem)
                    || paperStack.getCount() < 1) return true;

            PhotoData photoData = sdData.photos().get(0);
            ItemStack photoStack = new ItemStack(ModItems.PHOTO);
            photoStack.set(ModDataComponents.PHOTO_DATA, photoData);
            if (!player.getInventory().insertStack(photoStack)) {
                player.dropItem(photoStack, false);
            }
            paperStack.decrement(1);
            // Remove first photo from SD
            java.util.List<PhotoData> remaining = new java.util.ArrayList<>(sdData.photos());
            remaining.remove(0);
            sdStack.set(ModDataComponents.SD_CARD, new SdCardData(
                    java.util.Collections.unmodifiableList(remaining), sdData.capacity()));
            inventory.markDirty();
            return true;
        }
        return false;
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
                if (stack.getItem() instanceof SdCardItem) {
                    if (!insertItem(stack, 0, 1, false)) return ItemStack.EMPTY;
                } else if (stack.getItem() instanceof PhotoPaperItem) {
                    if (!insertItem(stack, 1, 2, false)) return ItemStack.EMPTY;
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
