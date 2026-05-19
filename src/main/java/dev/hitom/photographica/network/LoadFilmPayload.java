package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** C2S: load the first available FilmRoll from inventory into the held FilmCamera. */
public record LoadFilmPayload() implements CustomPayload {
	public static final CustomPayload.Id<LoadFilmPayload> ID =
			new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "load_film"));

	public static final PacketCodec<RegistryByteBuf, LoadFilmPayload> CODEC =
			PacketCodec.unit(new LoadFilmPayload());

	@Override
	public Id<? extends CustomPayload> getId() { return ID; }
}
