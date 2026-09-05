package dev.hitom.photographica.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.FilmRollItem;
import dev.hitom.photographica.item.LensItem;
import dev.hitom.photographica.item.SdCardItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;

/**
 * Everything physically fitted to a camera body — the parts that occupy a slot rather than a
 * dial position. Holds real {@link ItemStack}s, not flattened stats, because these are objects
 * you own: a half-drained battery has to come back out half-drained, and an SD card has to come
 * out still holding its photos.
 *
 * <h2>Why the old components still exist</h2>
 * The optics, exposure and gallery code all read the lens from {@link CameraSettings#lensType()},
 * the film from {@link FilmRollData} and the card from {@link SdCardData}. Rather than rewrite
 * every one of those paths, those components are kept as a <em>derived cache</em> of what is in
 * these slots: {@link #syncDerived} recomputes them after any change here, so this record is the
 * single source of truth while everything downstream keeps working unmodified.
 */
public record CameraGear(ItemStack lens, ItemStack storage, ItemStack battery, ItemStack flash) {

    public static final CameraGear EMPTY =
            new CameraGear(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);

    /** Slot order, shared by the screen handler and this record's accessors. */
    public static final int SLOT_LENS = 0;
    public static final int SLOT_STORAGE = 1;
    public static final int SLOT_BATTERY = 2;
    public static final int SLOT_FLASH = 3;
    public static final int SLOT_COUNT = 4;

    public static final Codec<CameraGear> CODEC = RecordCodecBuilder.create(i -> i.group(
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("lens", ItemStack.EMPTY).forGetter(CameraGear::lens),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("storage", ItemStack.EMPTY).forGetter(CameraGear::storage),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("battery", ItemStack.EMPTY).forGetter(CameraGear::battery),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("flash", ItemStack.EMPTY).forGetter(CameraGear::flash)
    ).apply(i, CameraGear::new));

    public static final PacketCodec<RegistryByteBuf, CameraGear> PACKET_CODEC = new PacketCodec<>() {
        @Override
        public CameraGear decode(RegistryByteBuf buf) {
            ItemStack lens = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
            ItemStack storage = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
            ItemStack battery = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
            ItemStack flash = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
            return new CameraGear(lens, storage, battery, flash);
        }

        @Override
        public void encode(RegistryByteBuf buf, CameraGear v) {
            ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, v.lens);
            ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, v.storage);
            ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, v.battery);
            ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, v.flash);
        }
    };

    public boolean hasBattery() { return !battery.isEmpty(); }
    public boolean hasFlash()   { return !flash.isEmpty(); }
    public boolean hasLens()    { return !lens.isEmpty(); }
    public boolean hasStorage() { return !storage.isEmpty(); }

    public CameraGear withBattery(ItemStack stack) { return new CameraGear(lens, storage, stack, flash); }
    public CameraGear withFlash(ItemStack stack)   { return new CameraGear(lens, storage, battery, stack); }
    public CameraGear withLens(ItemStack stack)    { return new CameraGear(stack, storage, battery, flash); }
    public CameraGear withStorage(ItemStack stack) { return new CameraGear(lens, stack, battery, flash); }

    public ItemStack get(int slot) {
        return switch (slot) {
            case SLOT_LENS -> lens;
            case SLOT_STORAGE -> storage;
            case SLOT_BATTERY -> battery;
            case SLOT_FLASH -> flash;
            default -> ItemStack.EMPTY;
        };
    }

    public CameraGear with(int slot, ItemStack stack) {
        return switch (slot) {
            case SLOT_LENS -> withLens(stack);
            case SLOT_STORAGE -> withStorage(stack);
            case SLOT_BATTERY -> withBattery(stack);
            case SLOT_FLASH -> withFlash(stack);
            default -> this;
        };
    }

    /** Which items each slot will physically accept. */
    public static boolean accepts(int slot, ItemStack stack) {
        return switch (slot) {
            case SLOT_LENS -> stack.getItem() instanceof LensItem;
            case SLOT_STORAGE -> stack.getItem() instanceof SdCardItem || stack.getItem() instanceof FilmRollItem;
            case SLOT_BATTERY -> stack.getItem() instanceof dev.hitom.photographica.item.BatteryItem;
            case SLOT_FLASH -> stack.getItem() instanceof dev.hitom.photographica.item.FlashItem;
            default -> false;
        };
    }

    /** Any body this gear system applies to. Video cameras are excluded — they have their own
     *  fixed lens and recording flow and no slots to fill. */
    public static boolean isCamera(ItemStack stack) {
        return stack.getItem() instanceof CameraItem || stack.getItem() instanceof FilmCameraItem;
    }

    public static CameraGear of(ItemStack camera) {
        CameraGear gear = camera.get(ModDataComponents.CAMERA_GEAR);
        return gear == null ? EMPTY : gear;
    }

    /**
     * Writes {@code gear} onto {@code camera} and rebuilds every derived component from it.
     * Call this instead of setting {@link ModDataComponents#CAMERA_GEAR} directly — the whole
     * point of the derived cache is that it can never disagree with the slots.
     */
    public static void install(ItemStack camera, CameraGear gear) {
        camera.set(ModDataComponents.CAMERA_GEAR, gear);
        syncDerived(camera, gear);
    }

    /**
     * Rebuilds {@link CameraSettings#lensType()}, {@link SdCardData} and {@link FilmRollData} to
     * match what is actually in the slots.
     *
     * <p>Removing a lens resets the focal length along with it: leaving the old value behind
     * would have the viewfinder and the saved photo rendered at a focal length no fitted lens
     * can produce.
     */
    public static void syncDerived(ItemStack camera, CameraGear gear) {
        boolean isFilmBody = camera.getItem() instanceof FilmCameraItem;
        CameraSettings settings = isFilmBody ? FilmCameraItem.getSettings(camera) : CameraItem.getSettings(camera);

        int lensType = gear.hasLens() && gear.lens().getItem() instanceof LensItem lensItem
                ? lensItem.lensKind
                : LensKind.NONE;
        int focal = lensType == LensKind.NONE
                ? settings.focalLengthMm()
                : LensKind.clampFocalLength(lensType, settings.focalLengthMm());
        if (lensType != settings.lensType()) {
            // Lens actually changed — snap to that lens's own default rather than clamping the
            // outgoing lens's value, which for e.g. 14mm -> 70-200mm would silently land at the
            // extreme end of the new range.
            focal = LensKind.defaultFocalLength(lensType);
        }

        CameraSettings updated = new CameraSettings(
                settings.aperture(), settings.shutterSpeedIdx(), settings.iso(), settings.focusDistance(),
                focal, lensType, settings.filmType(), settings.remainingShots(),
                settings.exposureMode(), settings.focusMode(), settings.autoWind(),
                settings.timerSeconds(), settings.motionBlur(), settings.focusPeaking());
        if (isFilmBody) {
            FilmCameraItem.setSettings(camera, updated);
        } else {
            CameraItem.setSettings(camera, updated);
        }

        // Storage. The card/roll item in the slot owns its own contents; the camera just
        // mirrors them so the existing capture and gallery paths keep reading what they always
        // read. An empty slot removes the mirror entirely, which is what those paths already
        // treat as "nothing loaded".
        ItemStack storage = gear.storage();
        if (storage.getItem() instanceof SdCardItem) {
            SdCardData sd = storage.get(ModDataComponents.SD_CARD);
            camera.set(ModDataComponents.SD_CARD, sd == null ? SdCardData.forCard(storage) : sd);
            camera.remove(ModDataComponents.FILM_ROLL);
        } else if (storage.getItem() instanceof FilmRollItem) {
            FilmRollData film = storage.get(ModDataComponents.FILM_ROLL);
            if (film != null) camera.set(ModDataComponents.FILM_ROLL, film);
            camera.remove(ModDataComponents.SD_CARD);
        } else {
            camera.remove(ModDataComponents.SD_CARD);
            camera.remove(ModDataComponents.FILM_ROLL);
        }
    }

    /**
     * Records a freshly taken photo onto the fitted card, updating both the card item in the
     * slot and the camera's mirror of it. Returns false when there is no card or it is full,
     * which is the caller's cue to hand the player a physical photo instead.
     */
    public static boolean storePhoto(ItemStack camera, PhotoData photo) {
        CameraGear gear = of(camera);
        if (!(gear.storage().getItem() instanceof SdCardItem)) return false;
        ItemStack card = gear.storage().copy();
        SdCardData sd = card.get(ModDataComponents.SD_CARD);
        if (sd == null) sd = SdCardData.forCard(card);
        if (sd.isFull()) return false;
        card.set(ModDataComponents.SD_CARD, sd.withPhoto(photo));
        install(camera, gear.withStorage(card));
        return true;
    }
}
