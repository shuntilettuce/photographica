package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** C2S: player pressed the "wind film" key — server flips FilmRollData.wound. */
public record WindFilmPayload() implements CustomPayload {
	public static final CustomPayload.Id<WindFilmPayload> ID =
			new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "wind_film"));

	public static final PacketCodec<RegistryByteBuf, WindFilmPayload> CODEC =
			PacketCodec.unit(new WindFilmPayload());

	@Override
	public Id<? extends CustomPayload> getId() { return ID; }
}
