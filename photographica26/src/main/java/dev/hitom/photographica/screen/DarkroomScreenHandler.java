package dev.hitom.photographica.screen;

import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.item.DeveloperTankItem;
import dev.hitom.photographica.item.DevelopedFilmItem;
import dev.hitom.photographica.item.ExposedFilmItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.registry.ModItems;
import dev.hitom.photographica.registry.ModScreenHandlers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.server.level.ServerPlayer;

public class DarkroomScreenHandler extends AbstractContainerMenu {

    private final Container inventory;

    /** Client-side constructor (called via MenuType). */
    public DarkroomScreenHandler(int syncId, Inventory playerInventory) {
        this(syncId, playerInventory, new SimpleContainer(5));
    }

    /** Server-side constructor. */
    public DarkroomScreenHandler(int syncId, Inventory playerInventory, Container inventory) {
        super(ModScreenHandlers.DARKROOM, syncId);
        checkContainerSize(inventory, 5);
        this.inventory = inventory;
        inventory.startOpen(playerInventory.player);

        // Slots 0-2: ExposedFilm slots (x=26,62,98 y=35)
        int[] filmX = {26, 62, 98};
        for (int i = 0; i < 3; i++) {
            final int slotIdx = i;
            addSlot(new Slot(inventory, slotIdx, filmX[i], 26) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return stack.getItem() instanceof ExposedFilmItem;
                }
            });
        }

        // Slot 3: DeveloperTank slot (x=134, y=35)
        addSlot(new Slot(inventory, 3, 134, 26) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof DeveloperTankItem;
            }
        });

        // Slot 4: Film camera slot (x=80, y=52) — extract film without fogging
        addSlot(new Slot(inventory, 4, 80, 52) {
            @Override
            public boolean mayPlace(ItemStack stack) {
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
    public void removed(Player player) {
        super.removed(player);
        this.inventory.stopOpen(player);
        clearContainer(player, this.inventory);
    }

    @Override
    public boolean stillValid(Player player) {
        return inventory.stillValid(player);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == 0) {
            // 現像: develop exposed films using developer tank
            ItemStack tankStack = inventory.getItem(3);
            if (tankStack.isEmpty() || !(tankStack.getItem() instanceof DeveloperTankItem)) {
                return true;
            }

            boolean inLight = ((ServerPlayer) player).serverLevel().getLightEmission(player.blockPosition()) > 7;
            int tanksUsed = 0;

            for (int i = 0; i < 3; i++) {
                ItemStack filmStack = inventory.getItem(i);
                if (filmStack.isEmpty() || !(filmStack.getItem() instanceof ExposedFilmItem)) {
                    continue;
                }

                // Check tank has durability left
                if (tankStack.getDamageValue() >= tankStack.getMaxDamage()) {
                    break;
                }

                FilmRollData film = filmStack.get(ModDataComponents.FILM_ROLL);
                if (film == null || film.exposures().isEmpty()) {
                    // Remove the empty film from slot
                    inventory.removeItem(i, inventory.getItem(i).getCount());
                    inventory.setChanged();
                    continue;
                }

                // Develop film: create DevelopedFilm item
                FilmRollData processedFilm = inLight ? film.withFoggedExposures() : film;
                ItemStack developedStack = new ItemStack(ModItems.DEVELOPED_FILM);
                developedStack.set(ModDataComponents.FILM_ROLL, processedFilm);
                if (!player.getInventory().add(developedStack)) {
                    player.drop(developedStack, false);
                }

                // Remove film from slot
                inventory.removeItem(i, inventory.getItem(i).getCount());
                inventory.setChanged();

                // Damage the tank by 1 per film developed
                tanksUsed++;
            }

            // Apply tank damage
            if (tanksUsed > 0 && !tankStack.isEmpty()) {
                for (int t = 0; t < tanksUsed; t++) {
                    if (tankStack.getDamageValue() < tankStack.getMaxDamage()) {
                        tankStack.setDamageValue(tankStack.getDamageValue() + 1);
                    }
                }
                if (tankStack.getDamageValue() >= tankStack.getMaxDamage()) {
                    inventory.setItem(3, ItemStack.EMPTY);
                }
                inventory.setChanged();
            }

            return true;
        }
        // button 1: カメラから取り出し＋現像（現像液タンクがあればネガに直結）
        if (id == 1) {
            ItemStack cameraStack = inventory.getItem(4);
            if (cameraStack.isEmpty() || !(cameraStack.getItem() instanceof FilmCameraItem)) return true;
            FilmRollData film = FilmCameraItem.getFilm(cameraStack);
            if (film.totalExposures() == 0) return true;

            cameraStack.remove(ModDataComponents.FILM_ROLL);
            inventory.setChanged();

            ItemStack tankStack = inventory.getItem(3);
            boolean hasTank = !tankStack.isEmpty()
                    && tankStack.getItem() instanceof DeveloperTankItem
                    && tankStack.getDamageValue() < tankStack.getMaxDamage();

            ItemStack out;
            if (hasTank) {
                // Develop directly — darkroom has no light so no fogging
                out = new ItemStack(ModItems.DEVELOPED_FILM);
                out.set(ModDataComponents.FILM_ROLL, film);
                tankStack.setDamageValue(tankStack.getDamageValue() + 1);
                if (tankStack.getDamageValue() >= tankStack.getMaxDamage()) {
                    inventory.setItem(3, ItemStack.EMPTY);
                }
                inventory.setChanged();
            } else {
                // No tank — extract as exposed film only
                out = new ItemStack(ModItems.EXPOSED_FILM);
                out.set(ModDataComponents.FILM_ROLL, film);
            }

            if (!player.getInventory().add(out)) {
                player.drop(out, false);
            }
            return true;
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();

            if (slotIndex < 5) {
                // From block entity to player inventory
                if (!moveItemStackTo(stack, 5, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // From player inventory to block entity
                if (stack.getItem() instanceof ExposedFilmItem) {
                    if (!moveItemStackTo(stack, 0, 3, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (stack.getItem() instanceof DeveloperTankItem) {
                    if (!moveItemStackTo(stack, 3, 4, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (stack.getItem() instanceof FilmCameraItem) {
                    if (!moveItemStackTo(stack, 4, 5, false)) {
                        return ItemStack.EMPTY;
                    }
                } else {
                    return ItemStack.EMPTY;
                }
            }

            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }
}
