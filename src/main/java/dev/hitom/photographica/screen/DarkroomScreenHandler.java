package dev.hitom.photographica.screen;

import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.item.DeveloperTankItem;
import dev.hitom.photographica.item.DevelopedFilmItem;
import dev.hitom.photographica.item.ExposedFilmItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.registry.ModItems;
import dev.hitom.photographica.registry.ModScreenHandlers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

public class DarkroomScreenHandler extends ScreenHandler {

    private final Inventory inventory;

    /** Client-side constructor (called via ScreenHandlerType). */
    public DarkroomScreenHandler(int syncId, PlayerInventory playerInventory) {
        this(syncId, playerInventory, new SimpleInventory(5));
    }

    /** Server-side constructor. */
    public DarkroomScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory) {
        super(ModScreenHandlers.DARKROOM, syncId);
        checkSize(inventory, 5);
        this.inventory = inventory;
        inventory.onOpen(playerInventory.player);

        // Slots 0-2: ExposedFilm slots (x=26,62,98 y=35)
        int[] filmX = {26, 62, 98};
        for (int i = 0; i < 3; i++) {
            final int slotIdx = i;
            addSlot(new Slot(inventory, slotIdx, filmX[i], 26) {
                @Override
                public boolean canInsert(ItemStack stack) {
                    return stack.getItem() instanceof ExposedFilmItem;
                }
            });
        }

        // Slot 3: DeveloperTank slot (x=134, y=35)
        addSlot(new Slot(inventory, 3, 134, 26) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return stack.getItem() instanceof DeveloperTankItem;
            }
        });

        // Slot 4: Film camera slot (x=80, y=58) — extract film without fogging
        addSlot(new Slot(inventory, 4, 80, 52) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return stack.getItem() instanceof FilmCameraItem;
            }
        });

        // Player inventory (3 rows × 9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 106 + row * 18));
            }
        }
        // Player hotbar
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 164));
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
        if (id == 0) {
            // 現像: develop exposed films using developer tank
            ItemStack tankStack = inventory.getStack(3);
            if (tankStack.isEmpty() || !(tankStack.getItem() instanceof DeveloperTankItem)) {
                return true;
            }

            boolean inLight = player.getWorld().getLightLevel(player.getBlockPos()) > 7;
            int tanksUsed = 0;

            for (int i = 0; i < 3; i++) {
                ItemStack filmStack = inventory.getStack(i);
                if (filmStack.isEmpty() || !(filmStack.getItem() instanceof ExposedFilmItem)) {
                    continue;
                }

                // Check tank has durability left
                if (tankStack.getDamage() >= tankStack.getMaxDamage()) {
                    break;
                }

                FilmRollData film = filmStack.get(ModDataComponents.FILM_ROLL);
                if (film == null || film.exposures().isEmpty()) {
                    // Remove the empty film from slot
                    inventory.removeStack(i);
                    inventory.markDirty();
                    continue;
                }

                // Develop film: create DevelopedFilm item
                FilmRollData processedFilm = inLight ? film.withFoggedExposures() : film;
                ItemStack developedStack = new ItemStack(ModItems.DEVELOPED_FILM);
                developedStack.set(ModDataComponents.FILM_ROLL, processedFilm);
                if (!player.getInventory().insertStack(developedStack)) {
                    player.dropItem(developedStack, false);
                }

                // Remove film from slot
                inventory.removeStack(i);
                inventory.markDirty();

                // Damage the tank by 1 per film developed
                tanksUsed++;
            }

            // Apply tank damage
            if (tanksUsed > 0 && !tankStack.isEmpty()) {
                for (int t = 0; t < tanksUsed; t++) {
                    if (tankStack.getDamage() < tankStack.getMaxDamage()) {
                        tankStack.setDamage(tankStack.getDamage() + 1);
                    }
                }
                if (tankStack.getDamage() >= tankStack.getMaxDamage()) {
                    inventory.setStack(3, ItemStack.EMPTY);
                }
                inventory.markDirty();
            }

            return true;
        }
        // button 1: カメラから取り出し＋現像（現像液タンクがあればネガに直結）
        if (id == 1) {
            ItemStack cameraStack = inventory.getStack(4);
            if (cameraStack.isEmpty() || !(cameraStack.getItem() instanceof FilmCameraItem)) return true;
            FilmRollData film = FilmCameraItem.getFilm(cameraStack);
            if (film.totalExposures() == 0) return true;

            FilmCameraItem.setFilm(cameraStack, FilmRollData.EMPTY.withWound(false));
            inventory.markDirty();

            ItemStack tankStack = inventory.getStack(3);
            boolean hasTank = !tankStack.isEmpty()
                    && tankStack.getItem() instanceof DeveloperTankItem
                    && tankStack.getDamage() < tankStack.getMaxDamage();

            ItemStack out;
            if (hasTank) {
                // Develop directly — darkroom has no light so no fogging
                out = new ItemStack(ModItems.DEVELOPED_FILM);
                out.set(ModDataComponents.FILM_ROLL, film);
                tankStack.setDamage(tankStack.getDamage() + 1);
                if (tankStack.getDamage() >= tankStack.getMaxDamage()) {
                    inventory.setStack(3, ItemStack.EMPTY);
                }
                inventory.markDirty();
            } else {
                // No tank — extract as exposed film only
                out = new ItemStack(ModItems.EXPOSED_FILM);
                out.set(ModDataComponents.FILM_ROLL, film);
            }

            if (!player.getInventory().insertStack(out)) {
                player.dropItem(out, false);
            }
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

            if (slotIndex < 5) {
                // From block entity to player inventory
                if (!insertItem(stack, 5, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // From player inventory to block entity
                if (stack.getItem() instanceof ExposedFilmItem) {
                    if (!insertItem(stack, 0, 3, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (stack.getItem() instanceof DeveloperTankItem) {
                    if (!insertItem(stack, 3, 4, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (stack.getItem() instanceof FilmCameraItem) {
                    if (!insertItem(stack, 4, 5, false)) {
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
