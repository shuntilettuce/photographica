package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.BatteryItem;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.DeveloperTankItem;
import dev.hitom.photographica.item.FlashItem;
import dev.hitom.photographica.item.DevelopedFilmItem;
import dev.hitom.photographica.item.DroneItem;
import dev.hitom.photographica.item.DroneRemoteItem;
import dev.hitom.photographica.item.ExposedFilmItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.FilmRollItem;
import dev.hitom.photographica.item.LensItem;
import dev.hitom.photographica.item.MirrorlessCameraItem;
import dev.hitom.photographica.item.PhotoItem;
import dev.hitom.photographica.item.PhotoPaperItem;
import dev.hitom.photographica.item.SdCardItem;
import dev.hitom.photographica.item.VideoCameraItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import java.util.function.Function;
//? if >=1.21.4 {
/*import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.equipment.EquipmentAsset;
import net.minecraft.item.equipment.EquipmentAssetKeys;
*///?}

public final class ModItems {
	private ModItems() {}

	public static final Item VIDEO_CAMERA        = reg("video_camera",         s -> new VideoCameraItem(chestEquippable(s)));
	public static final Item CAMERA              = reg("camera",               s -> new CameraItem(chestEquippable(s)));
	public static final Item FILM_CAMERA         = reg("film_camera",          s -> new FilmCameraItem(chestEquippable(s)));
	public static final Item MIRRORLESS_CAMERA   = reg("mirrorless_camera",    s -> new MirrorlessCameraItem(chestEquippable(s)));
	public static final Item LENS_PRIME_50       = reg("lens_prime_50mm",      s -> new LensItem(s, LensKind.PRIME_50MM));
	public static final Item LENS_ZOOM_24_70     = reg("lens_zoom_24_70mm",    s -> new LensItem(s, LensKind.ZOOM_24_70));
	public static final Item LENS_PRIME_35       = reg("lens_prime_35mm",      s -> new LensItem(s, LensKind.PRIME_35MM));
	public static final Item LENS_PRIME_85       = reg("lens_prime_85mm",      s -> new LensItem(s, LensKind.PRIME_85MM));
	public static final Item LENS_PRIME_14       = reg("lens_prime_14mm",      s -> new LensItem(s, LensKind.PRIME_14MM));
	public static final Item LENS_ZOOM_70_200    = reg("lens_zoom_70_200mm",   s -> new LensItem(s, LensKind.ZOOM_70_200));
	public static final Item LENS_MACRO_100      = reg("lens_macro_100mm",     s -> new LensItem(s, LensKind.MACRO_100));

	// Batteries — tiered the way vanilla tiers tools: identical to use, differing only in how
	// long they last. Capacities are in charge units; one photo costs 1, one tick of drone
	// flight costs 1 (see BatteryItem), so 1200 is a minute of flight or 1200 shots.
	public static final Item BATTERY_STANDARD    = reg("battery_standard",     s -> new BatteryItem(s, 1200));
	public static final Item BATTERY_EXTENDED    = reg("battery_extended",     s -> new BatteryItem(s, 4800));
	public static final Item BATTERY_PRO         = reg("battery_pro",          s -> new BatteryItem(s, 14400));

	// Flashes — rated by reach rather than raw brightness, so the upgrade path is "how far can
	// I light" instead of "how bright is the picture". Bigger units cost more per firing.
	public static final Item FLASH_COMPACT       = reg("flash_compact",        s -> new FlashItem(s, 8, 10));
	public static final Item FLASH_SPEEDLIGHT    = reg("flash_speedlight",     s -> new FlashItem(s, 20, 25));
	public static final Item PHOTO               = reg("photo",                s -> new PhotoItem(s));
	// Film rolls — one item per emulsion type
	public static final Item FILM_ROLL_COLOR     = reg("film_roll_color",      s -> new FilmRollItem(s, FilmKind.COLOR_400));
	public static final Item FILM_ROLL_COLOR_100 = reg("film_roll_color_100",  s -> new FilmRollItem(s, FilmKind.COLOR_100));
	public static final Item FILM_ROLL_COLOR_1600= reg("film_roll_color_1600", s -> new FilmRollItem(s, FilmKind.COLOR_1600));
	public static final Item FILM_ROLL_BW        = reg("film_roll_bw",         s -> new FilmRollItem(s, FilmKind.BW_400));
	public static final Item FILM_ROLL_COLOR_24  = reg("film_roll_color_24",   s -> new FilmRollItem(s, FilmKind.COLOR_400_24));
	public static final Item EXPOSED_FILM        = reg("exposed_film",         s -> new ExposedFilmItem(s));
	// Developer tank — 32 uses before it needs replacing
	public static final Item DEVELOPER_TANK      = reg("developer_tank",       s -> new DeveloperTankItem(s.maxDamage(32)));
	public static final Item DEVELOPED_FILM      = reg("developed_film",       s -> new DevelopedFilmItem(s.maxCount(1)));
	// SD cards, tiered like vanilla tools — same card, more room. Capacities are photo counts;
	// the metadata for each stored photo rides along on the card's own ItemStack, so the top
	// tier is deliberately the point where that starts to be worth moving server-side.
	public static final Item SD_CARD             = reg("sd_card",              s -> new SdCardItem(s.maxCount(1), 64));
	public static final Item SD_CARD_LARGE       = reg("sd_card_large",        s -> new SdCardItem(s.maxCount(1), 256));
	public static final Item SD_CARD_XL          = reg("sd_card_xl",           s -> new SdCardItem(s.maxCount(1), 1024));
	public static final Item PHOTO_PAPER         = reg("photo_paper",          s -> new PhotoPaperItem(s));
	// A physical binder for printed photos — the paper counterpart to an SD card. See AlbumData
	// for why it holds real ItemStacks rather than flattened metadata.
	public static final Item ALBUM               = reg("album",                s -> new dev.hitom.photographica.item.AlbumItem(s));
	public static final Item DRONE               = reg("drone",                s -> new DroneItem(s));
	public static final Item DRONE_CONTROLLER    = reg("drone_controller",     s -> new DroneRemoteItem(s));

	// Cameras occupy the chest slot. equipOnInteract + swappable lets the camera be
	// right-clicked onto armor stands (tripods). The EquipmentAsset key need not
	// resolve to a JSON asset — rendering is handled separately.
	//? if >=1.21.11 {
	/*private static Item.Settings chestEquippable(Item.Settings s) {
		RegistryKey<EquipmentAsset> asset = RegistryKey.of(EquipmentAssetKeys.REGISTRY_KEY,
				Identifier.of(Photographica.MOD_ID, "camera"));
		return s.component(DataComponentTypes.EQUIPPABLE,
				EquippableComponent.builder(EquipmentSlot.CHEST)
						.model(asset)
						.swappable(true)
						.equipOnInteract(true)
						.build());
	}
	*///?} else if >=1.21.4 {
	/*private static Item.Settings chestEquippable(Item.Settings s) {
		// 1.21.4 lacks EquippableComponent.Builder#equipOnInteract (added in 1.21.5);
		// swappable=true still lets the camera be placed on armor-stand tripods.
		RegistryKey<EquipmentAsset> asset = RegistryKey.of(EquipmentAssetKeys.REGISTRY_KEY,
				Identifier.of(Photographica.MOD_ID, "camera"));
		return s.component(DataComponentTypes.EQUIPPABLE,
				EquippableComponent.builder(EquipmentSlot.CHEST)
						.model(asset)
						.swappable(true)
						.build());
	}
	*///?} else {
	private static Item.Settings chestEquippable(Item.Settings s) {
		return s;
	}
	//?}

	//? if >=1.21.4 {
	/*private static <T extends Item> T reg(String name, Function<Item.Settings, T> factory) {
		RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Photographica.MOD_ID, name));
		return Registry.register(Registries.ITEM, key, factory.apply(new Item.Settings().registryKey(key)));
	}
	*///?} else {
	private static <T extends Item> T reg(String name, Function<Item.Settings, T> factory) {
		return Registry.register(Registries.ITEM, Identifier.of(Photographica.MOD_ID, name), factory.apply(new Item.Settings()));
	}
	//?}

	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
			entries.add(VIDEO_CAMERA);
			entries.add(CAMERA);
			entries.add(MIRRORLESS_CAMERA);
			entries.add(FILM_CAMERA);
			entries.add(LENS_PRIME_50);
			entries.add(LENS_ZOOM_24_70);
			entries.add(LENS_PRIME_35);
			entries.add(LENS_PRIME_85);
			entries.add(LENS_PRIME_14);
			entries.add(LENS_ZOOM_70_200);
			entries.add(LENS_MACRO_100);
			entries.add(BATTERY_STANDARD);
			entries.add(BATTERY_EXTENDED);
			entries.add(BATTERY_PRO);
			entries.add(FLASH_COMPACT);
			entries.add(FLASH_SPEEDLIGHT);
			entries.add(ALBUM);
			entries.add(FilmRollItem.stackOf(FILM_ROLL_COLOR,     FilmKind.COLOR_400));
			entries.add(FilmRollItem.stackOf(FILM_ROLL_COLOR_100, FilmKind.COLOR_100));
			entries.add(FilmRollItem.stackOf(FILM_ROLL_COLOR_1600,FilmKind.COLOR_1600));
			entries.add(FilmRollItem.stackOf(FILM_ROLL_BW,        FilmKind.BW_400));
			entries.add(FilmRollItem.stackOf(FILM_ROLL_COLOR_24,  FilmKind.COLOR_400_24));
			entries.add(EXPOSED_FILM);
			entries.add(DEVELOPER_TANK);
			entries.add(DEVELOPED_FILM);
			entries.add(SD_CARD);
			entries.add(SD_CARD_LARGE);
			entries.add(SD_CARD_XL);
			entries.add(PHOTO_PAPER);
			entries.add(PHOTO);
			entries.add(DRONE);
			entries.add(DRONE_CONTROLLER);
		});
	}
}
