package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: player pressed the "wind film" key — server flips FilmRollData.wound. */
public record WindFilmPayload() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<WindFilmPayload> ID =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "wind_film"));

	public static final StreamCodec<RegistryFriendlyByteBuf, WindFilmPayload> CODEC =
			StreamCodec.unit(new WindFilmPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() { return ID; }
}
