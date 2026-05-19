package dev.hitom.photographica.component;

import dev.hitom.photographica.Photographica;
import net.minecraft.component.ComponentType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModDataComponents {
	private ModDataComponents() {}

	public static final ComponentType<CameraSettings> CAMERA_SETTINGS = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			Identifier.of(Photographica.MOD_ID, "camera_settings"),
			ComponentType.<CameraSettings>builder()
					.codec(CameraSettings.CODEC)
					.packetCodec(CameraSettings.PACKET_CODEC)
					.build()
	);

	public static final ComponentType<PhotoData> PHOTO_DATA = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			Identifier.of(Photographica.MOD_ID, "photo_data"),
			ComponentType.<PhotoData>builder()
					.codec(PhotoData.CODEC)
					.packetCodec(PhotoData.PACKET_CODEC)
					.build()
	);

	public static final ComponentType<FilmRollData> FILM_ROLL = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			Identifier.of(Photographica.MOD_ID, "film_roll"),
			ComponentType.<FilmRollData>builder()
					.codec(FilmRollData.CODEC)
					.packetCodec(FilmRollData.PACKET_CODEC)
					.build()
	);

	public static void register() {
		// Class init is enough; this method just forces it.
	}
}
