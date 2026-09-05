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

	public static final ComponentType<SdCardData> SD_CARD = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			Identifier.of(Photographica.MOD_ID, "sd_card"),
			ComponentType.<SdCardData>builder()
					.codec(SdCardData.CODEC)
					.packetCodec(SdCardData.PACKET_CODEC)
					.build()
	);

	public static final ComponentType<VideoSettings> VIDEO_SETTINGS = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			Identifier.of(Photographica.MOD_ID, "video_settings"),
			ComponentType.<VideoSettings>builder()
					.codec(VideoSettings.CODEC)
					.packetCodec(VideoSettings.PACKET_CODEC)
					.build()
	);

	/** The drone frequency a {@code DroneRemoteItem} has been paired to — absent entirely
	 *  (rather than some sentinel like -1) means "never paired to anything yet". */
	public static final ComponentType<Integer> DRONE_FREQUENCY = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			Identifier.of(Photographica.MOD_ID, "drone_frequency"),
			ComponentType.<Integer>builder()
					.codec(com.mojang.serialization.Codec.INT)
					.packetCodec(net.minecraft.network.codec.PacketCodecs.INTEGER)
					.build()
	);

	/** Battery and flash installed in a camera body — see {@link CameraGear}. */
	public static final ComponentType<CameraGear> CAMERA_GEAR = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			Identifier.of(Photographica.MOD_ID, "camera_gear"),
			ComponentType.<CameraGear>builder()
					.codec(CameraGear.CODEC)
					.packetCodec(CameraGear.PACKET_CODEC)
					.build()
	);

	/** Remaining charge of a {@code BatteryItem} stack. Absent means "never used yet", which
	 *  {@code BatteryItem#getCharge} reads as a full cell rather than an empty one. */
	public static final ComponentType<Integer> BATTERY_CHARGE = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			Identifier.of(Photographica.MOD_ID, "battery_charge"),
			ComponentType.<Integer>builder()
					.codec(com.mojang.serialization.Codec.INT)
					.packetCodec(net.minecraft.network.codec.PacketCodecs.INTEGER)
					.build()
	);

	/** A physical album's contents — see {@link AlbumData}. */
	public static final ComponentType<AlbumData> ALBUM = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			Identifier.of(Photographica.MOD_ID, "album"),
			ComponentType.<AlbumData>builder()
					.codec(AlbumData.CODEC)
					.packetCodec(AlbumData.PACKET_CODEC)
					.build()
	);

	public static void register() {
		// Class init is enough; this method just forces it.
	}
}
