package dev.hitom.photographica.component;

import dev.hitom.photographica.Photographica;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;

public final class ModDataComponents {
	private ModDataComponents() {}

	public static final DataComponentType<CameraSettings> CAMERA_SETTINGS = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "camera_settings"),
			DataComponentType.<CameraSettings>builder()
					.persistent(CameraSettings.CODEC)
					.networkSynchronized(CameraSettings.PACKET_CODEC)
					.build()
	);

	public static final DataComponentType<PhotoData> PHOTO_DATA = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "photo_data"),
			DataComponentType.<PhotoData>builder()
					.persistent(PhotoData.CODEC)
					.networkSynchronized(PhotoData.PACKET_CODEC)
					.build()
	);

	public static final DataComponentType<FilmRollData> FILM_ROLL = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "film_roll"),
			DataComponentType.<FilmRollData>builder()
					.persistent(FilmRollData.CODEC)
					.networkSynchronized(FilmRollData.PACKET_CODEC)
					.build()
	);

	public static final DataComponentType<SdCardData> SD_CARD = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "sd_card"),
			DataComponentType.<SdCardData>builder()
					.persistent(SdCardData.CODEC)
					.networkSynchronized(SdCardData.PACKET_CODEC)
					.build()
	);

	public static final DataComponentType<VideoSettings> VIDEO_SETTINGS = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "video_settings"),
			DataComponentType.<VideoSettings>builder()
					.persistent(VideoSettings.CODEC)
					.networkSynchronized(VideoSettings.PACKET_CODEC)
					.build()
	);

	public static void register() {
		// Class init is enough; this method just forces it.
	}
}
