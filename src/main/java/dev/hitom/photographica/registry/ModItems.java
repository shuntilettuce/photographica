package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.DeveloperTankItem;
import dev.hitom.photographica.item.ExposedFilmItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.FilmRollItem;
import dev.hitom.photographica.item.LensItem;
import dev.hitom.photographica.item.PhotoItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModItems {
	private ModItems() {}

	public static final Item CAMERA             = reg("camera",              new CameraItem(new Item.Settings()));
	public static final Item FILM_CAMERA        = reg("film_camera",         new FilmCameraItem(new Item.Settings()));
	public static final Item LENS_PRIME_50      = reg("lens_prime_50mm",     new LensItem(new Item.Settings(), LensKind.PRIME_50MM));
	public static final Item LENS_ZOOM_24_70    = reg("lens_zoom_24_70mm",   new LensItem(new Item.Settings(), LensKind.ZOOM_24_70));
	public static final Item PHOTO              = reg("photo",               new PhotoItem(new Item.Settings()));
	// Film rolls — one item per emulsion type
	public static final Item FILM_ROLL_COLOR    = reg("film_roll_color",     new FilmRollItem(new Item.Settings(), FilmKind.COLOR_400));
	public static final Item FILM_ROLL_COLOR_100= reg("film_roll_color_100", new FilmRollItem(new Item.Settings(), FilmKind.COLOR_100));
	public static final Item FILM_ROLL_COLOR_1600=reg("film_roll_color_1600",new FilmRollItem(new Item.Settings(), FilmKind.COLOR_1600));
	public static final Item FILM_ROLL_BW       = reg("film_roll_bw",        new FilmRollItem(new Item.Settings(), FilmKind.BW_400));
	public static final Item FILM_ROLL_COLOR_24 = reg("film_roll_color_24",  new FilmRollItem(new Item.Settings(), FilmKind.COLOR_400_24));
	public static final Item EXPOSED_FILM       = reg("exposed_film",        new ExposedFilmItem(new Item.Settings()));
	// Developer tank — 32 uses before it needs replacing
	public static final Item DEVELOPER_TANK     = reg("developer_tank",      new DeveloperTankItem(new Item.Settings().maxDamage(32)));

	private static Item reg(String name, Item item) {
		return Registry.register(Registries.ITEM, Identifier.of(Photographica.MOD_ID, name), item);
	}

	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
			entries.add(CAMERA);
			entries.add(FILM_CAMERA);
			entries.add(LENS_PRIME_50);
			entries.add(LENS_ZOOM_24_70);
			entries.add(FilmRollItem.stackOf(FILM_ROLL_COLOR,     FilmKind.COLOR_400));
			entries.add(FilmRollItem.stackOf(FILM_ROLL_COLOR_100, FilmKind.COLOR_100));
			entries.add(FilmRollItem.stackOf(FILM_ROLL_COLOR_1600,FilmKind.COLOR_1600));
			entries.add(FilmRollItem.stackOf(FILM_ROLL_BW,        FilmKind.BW_400));
			entries.add(FilmRollItem.stackOf(FILM_ROLL_COLOR_24,  FilmKind.COLOR_400_24));
			entries.add(EXPOSED_FILM);
			entries.add(DEVELOPER_TANK);
			entries.add(PHOTO);
		});
	}
}
