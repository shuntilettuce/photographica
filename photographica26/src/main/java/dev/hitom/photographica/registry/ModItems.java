package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.DeveloperTankItem;
import dev.hitom.photographica.item.DevelopedFilmItem;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;

import java.util.function.Function;

public final class ModItems {
	private ModItems() {}

	public static final Item VIDEO_CAMERA        = reg("video_camera",        s -> new VideoCameraItem(chestEquippable(s)));
	public static final Item CAMERA              = reg("camera",              s -> new CameraItem(chestEquippable(s)));
	public static final Item FILM_CAMERA         = reg("film_camera",         s -> new FilmCameraItem(chestEquippable(s)));
	public static final Item MIRRORLESS_CAMERA   = reg("mirrorless_camera",   s -> new MirrorlessCameraItem(chestEquippable(s)));
	public static final Item LENS_PRIME_50       = reg("lens_prime_50mm",     s -> new LensItem(s, LensKind.PRIME_50MM));
	public static final Item LENS_ZOOM_24_70     = reg("lens_zoom_24_70mm",   s -> new LensItem(s, LensKind.ZOOM_24_70));
	public static final Item LENS_PRIME_35       = reg("lens_prime_35mm",     s -> new LensItem(s, LensKind.PRIME_35MM));
	public static final Item LENS_PRIME_85       = reg("lens_prime_85mm",     s -> new LensItem(s, LensKind.PRIME_85MM));
	public static final Item LENS_PRIME_14       = reg("lens_prime_14mm",     s -> new LensItem(s, LensKind.PRIME_14MM));
	public static final Item LENS_ZOOM_70_200    = reg("lens_zoom_70_200mm",  s -> new LensItem(s, LensKind.ZOOM_70_200));
	public static final Item LENS_MACRO_100      = reg("lens_macro_100mm",    s -> new LensItem(s, LensKind.MACRO_100));
	public static final Item PHOTO               = reg("photo",               PhotoItem::new);
	public static final Item FILM_ROLL_COLOR     = reg("film_roll_color",     s -> new FilmRollItem(s, FilmKind.COLOR_400));
	public static final Item FILM_ROLL_COLOR_100 = reg("film_roll_color_100", s -> new FilmRollItem(s, FilmKind.COLOR_100));
	public static final Item FILM_ROLL_COLOR_1600= reg("film_roll_color_1600",s -> new FilmRollItem(s, FilmKind.COLOR_1600));
	public static final Item FILM_ROLL_BW        = reg("film_roll_bw",        s -> new FilmRollItem(s, FilmKind.BW_400));
	public static final Item FILM_ROLL_COLOR_24  = reg("film_roll_color_24",  s -> new FilmRollItem(s, FilmKind.COLOR_400_24));
	public static final Item EXPOSED_FILM        = reg("exposed_film",        ExposedFilmItem::new);
	public static final Item DEVELOPER_TANK      = reg("developer_tank",      s -> new DeveloperTankItem(s.durability(32)));
	public static final Item DEVELOPED_FILM      = reg("developed_film",      s -> new DevelopedFilmItem(s.stacksTo(1)));
	public static final Item SD_CARD             = reg("sd_card",             s -> new SdCardItem(s.stacksTo(1)));
	public static final Item PHOTO_PAPER         = reg("photo_paper",         PhotoPaperItem::new);

	private static Item reg(String name, Function<Item.Properties, Item> f) {
		ResourceKey<Item> k = ResourceKey.create(Registries.ITEM,
				ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, name));
		return Registry.register(BuiltInRegistries.ITEM,
				ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, name),
				f.apply(new Item.Properties().setId(k)));
	}

	// Cameras occupy the chest slot. The EquipmentAsset key need not resolve to an
	// existing JSON — Fabric's ArmorRenderer intercepts the rendering before vanilla
	// looks for the asset file.
	private static Item.Properties chestEquippable(Item.Properties s) {
		return s.component(DataComponents.EQUIPPABLE,
			Equippable.builder(EquipmentSlot.CHEST)
				.setAsset(ResourceKey.create(
					EquipmentAsset.REGISTRY_KEY,
					ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "camera")))
				.build());
	}

	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
			entries.accept(VIDEO_CAMERA);
			entries.accept(CAMERA);
			entries.accept(MIRRORLESS_CAMERA);
			entries.accept(FILM_CAMERA);
			entries.accept(LENS_PRIME_50);
			entries.accept(LENS_ZOOM_24_70);
			entries.accept(LENS_PRIME_35);
			entries.accept(LENS_PRIME_85);
			entries.accept(LENS_PRIME_14);
			entries.accept(LENS_ZOOM_70_200);
			entries.accept(LENS_MACRO_100);
			entries.accept(FilmRollItem.stackOf(FILM_ROLL_COLOR,     FilmKind.COLOR_400));
			entries.accept(FilmRollItem.stackOf(FILM_ROLL_COLOR_100, FilmKind.COLOR_100));
			entries.accept(FilmRollItem.stackOf(FILM_ROLL_COLOR_1600,FilmKind.COLOR_1600));
			entries.accept(FilmRollItem.stackOf(FILM_ROLL_BW,        FilmKind.BW_400));
			entries.accept(FilmRollItem.stackOf(FILM_ROLL_COLOR_24,  FilmKind.COLOR_400_24));
			entries.accept(EXPOSED_FILM);
			entries.accept(DEVELOPER_TANK);
			entries.accept(DEVELOPED_FILM);
			entries.accept(SD_CARD);
			entries.accept(PHOTO_PAPER);
			entries.accept(PHOTO);
		});
	}
}
