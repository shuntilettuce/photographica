package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.CameraItem;
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

	public static final Item CAMERA = reg("camera", new CameraItem(new Item.Settings()));
	public static final Item LENS_PRIME_50 = reg("lens_prime_50mm", new LensItem(new Item.Settings(), LensKind.PRIME_50MM));
	public static final Item LENS_ZOOM_24_70 = reg("lens_zoom_24_70mm", new LensItem(new Item.Settings(), LensKind.ZOOM_24_70));
	public static final Item PHOTO = reg("photo", new PhotoItem(new Item.Settings()));

	private static Item reg(String name, Item item) {
		return Registry.register(Registries.ITEM, Identifier.of(Photographica.MOD_ID, name), item);
	}

	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
			entries.add(CAMERA);
			entries.add(LENS_PRIME_50);
			entries.add(LENS_ZOOM_24_70);
			entries.add(PHOTO);
		});
	}
}
