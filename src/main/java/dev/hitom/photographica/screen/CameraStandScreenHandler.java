package dev.hitom.photographica.screen;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.SdCardData;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.ExposedFilmItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.FilmRollItem;
import dev.hitom.photographica.item.LensItem;
import dev.hitom.photographica.item.MirrorlessCameraItem;
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

public class CameraStandScreenHandler extends ScreenHandler {

    private final Inventory inventory;

    /** Client-side constructor (called via ScreenHandlerRegistry). */
    public CameraStandScreenHandler(int syncId, PlayerInventory playerInventory) {
        this(syncId, playerInventory, new SimpleInventory(3));
    }

    /** Server-side constructor. */
    public CameraStandScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory) {
        super(ModScreenHandlers.CAMERA_STAND, syncId);
        checkSize(inventory, 3);
        this.inventory = inventory;
        inventory.onOpen(playerInventory.player);

        // Slot 0: Camera slot (x=35, y=35)
        addSlot(new Slot(inventory, 0, 35, 35) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return stack.getItem() instanceof CameraItem
                        || stack.getItem() instanceof FilmCameraItem
                        || stack.getItem() instanceof MirrorlessCameraItem;
            }
        });

        // Slot 1: Lens slot (x=71, y=35)
        addSlot(new Slot(inventory, 1, 71, 35) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return stack.getItem() instanceof LensItem;
            }
        });

        // Slot 2: Film/SD card slot (x=107, y=35)
        addSlot(new Slot(inventory, 2, 107, 35) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return stack.getItem() instanceof FilmRollItem
                        || stack.getItem() instanceof SdCardItem;
            }
        });

        // Player inventory (3 rows × 9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        // Player hotbar
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return inventory.canPlayerUse(player);
    }

    @Override
    public boolean onButtonClick(PlayerEntity player, int id) {
        switch (id) {
            case 0 -> {
                // レンズ装着: mount lens from slot 1 onto camera in slot 0
                ItemStack cameraStack = inventory.getStack(0);
                ItemStack lensStack = inventory.getStack(1);
                if (cameraStack.isEmpty() || lensStack.isEmpty()) return true;
                if (!(lensStack.getItem() instanceof LensItem lensItem)) return true;

                int newLensType = lensItem.lensKind;
                CameraSettings current;
                if (cameraStack.getItem() instanceof FilmCameraItem) {
                    current = FilmCameraItem.getSettings(cameraStack);
                } else if (cameraStack.getItem() instanceof CameraItem) {
                    current = CameraItem.getSettings(cameraStack);
                } else {
                    return true;
                }

                int newFocal = LensKind.clampFocalLength(newLensType, current.focalLengthMm());
                if (newFocal == 0) newFocal = LensKind.defaultFocalLength(newLensType);

                CameraSettings updated = new CameraSettings(
                        current.aperture(), current.shutterSpeedIdx(), current.iso(),
                        current.focusDistance(), newFocal, newLensType,
                        current.filmType(), current.remainingShots(),
                        current.exposureMode(), current.focusMode(), current.autoWind());

                if (cameraStack.getItem() instanceof FilmCameraItem) {
                    FilmCameraItem.setSettings(cameraStack, updated);
                } else {
                    CameraItem.setSettings(cameraStack, updated);
                }

                // Remove the lens from slot 1
                inventory.removeStack(1);
                inventory.markDirty();
                return true;
            }
            case 1 -> {
                // 装填: film camera → load film; digital camera → load SD card
                ItemStack cameraStack = inventory.getStack(0);
                ItemStack mediaStack = inventory.getStack(2);
                if (cameraStack.isEmpty() || mediaStack.isEmpty()) return true;

                if (cameraStack.getItem() instanceof FilmCameraItem) {
                    // Film camera: load FilmRoll
                    if (FilmCameraItem.hasFilm(cameraStack)) return true;
                    if (!(mediaStack.getItem() instanceof FilmRollItem fr)) return true;
                    FilmRollData fresh = mediaStack.getOrDefault(ModDataComponents.FILM_ROLL,
                            FilmRollData.freshRoll(fr.filmType()));
                    FilmCameraItem.setFilm(cameraStack, fresh.withWound(true));
                    CameraSettings cur = FilmCameraItem.getSettings(cameraStack);
                    FilmCameraItem.setSettings(cameraStack, new CameraSettings(
                            cur.aperture(), cur.shutterSpeedIdx(), FilmKind.isoOf(fresh.filmType()),
                            cur.focusDistance(), cur.focalLengthMm(), cur.lensType(),
                            fresh.filmType(), fresh.totalExposures(),
                            cur.exposureMode(), cur.focusMode(), cur.autoWind()));
                    mediaStack.decrement(1);
                } else if (cameraStack.getItem() instanceof CameraItem) {
                    // Digital camera: load SD card
                    if (cameraStack.contains(ModDataComponents.SD_CARD)) return true;
                    if (!(mediaStack.getItem() instanceof SdCardItem)) return true;
                    SdCardData sdData = mediaStack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
                    cameraStack.set(ModDataComponents.SD_CARD, sdData);
                    mediaStack.decrement(1);
                }
                inventory.markDirty();
                return true;
            }
            case 2 -> {
                // 取り出し: film camera → unload film; digital camera → unload SD card
                ItemStack cameraStack = inventory.getStack(0);
                if (cameraStack.isEmpty()) return true;

                if (cameraStack.getItem() instanceof FilmCameraItem) {
                    FilmRollData film = FilmCameraItem.getFilm(cameraStack);
                    if (film.totalExposures() == 0) return true;
                    int light = player.getWorld().getLightLevel(player.getBlockPos());
                    if (light > 0 && !film.isEmpty()) {
                        film = film.withFoggedExposures();
                    }
                    ItemStack out;
                    if (film.isEmpty()) {
                        out = filmRollForType(film.filmType());
                    } else {
                        out = new ItemStack(ModItems.EXPOSED_FILM);
                        out.set(ModDataComponents.FILM_ROLL, film);
                    }
                    FilmCameraItem.setFilm(cameraStack, FilmRollData.EMPTY.withWound(false));
                    inventory.markDirty();
                    if (!player.getInventory().insertStack(out)) {
                        player.dropItem(out, false);
                    }
                } else if (cameraStack.getItem() instanceof CameraItem) {
                    if (!cameraStack.contains(ModDataComponents.SD_CARD)) return true;
                    SdCardData sdData = cameraStack.get(ModDataComponents.SD_CARD);
                    cameraStack.remove(ModDataComponents.SD_CARD);
                    inventory.markDirty();
                    ItemStack sdStack = new ItemStack(ModItems.SD_CARD);
                    sdStack.set(ModDataComponents.SD_CARD, sdData != null ? sdData : SdCardData.EMPTY);
                    if (!player.getInventory().insertStack(sdStack)) {
                        player.dropItem(sdStack, false);
                    }
                }
                return true;
            }
            default -> { return false; }
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (slot.hasStack()) {
            ItemStack stack = slot.getStack();
            result = stack.copy();

            if (slotIndex < 3) {
                // From block entity to player inventory
                if (!insertItem(stack, 3, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // From player inventory to block entity
                if (stack.getItem() instanceof CameraItem
                        || stack.getItem() instanceof FilmCameraItem
                        || stack.getItem() instanceof MirrorlessCameraItem) {
                    if (!insertItem(stack, 0, 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (stack.getItem() instanceof LensItem) {
                    if (!insertItem(stack, 1, 2, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (stack.getItem() instanceof FilmRollItem
                        || stack.getItem() instanceof SdCardItem) {
                    if (!insertItem(stack, 2, 3, false)) {
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

    private static ItemStack filmRollForType(int filmType) {
        net.minecraft.item.Item rollItem = switch (filmType) {
            case FilmKind.COLOR_100     -> ModItems.FILM_ROLL_COLOR_100;
            case FilmKind.COLOR_1600    -> ModItems.FILM_ROLL_COLOR_1600;
            case FilmKind.BW_400        -> ModItems.FILM_ROLL_BW;
            case FilmKind.COLOR_400_24  -> ModItems.FILM_ROLL_COLOR_24;
            default                     -> ModItems.FILM_ROLL_COLOR;
        };
        return FilmRollItem.stackOf(rollItem, filmType);
    }
}
