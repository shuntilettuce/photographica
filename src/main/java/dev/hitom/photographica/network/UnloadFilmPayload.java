package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** C2S: remove the film from the held FilmCamera and give it back as an ExposedFilm item. */
public record UnloadFilmPayload() implements CustomPayload {
	public static final CustomPayload.Id<UnloadFilmPayload> ID =
			new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "unload_film"));

	public static final PacketCodec<RegistryByteBuf, UnloadFilmPayload> CODEC =
			PacketCodec.unit(new UnloadFilmPayload());

	@Override
	public Id<? extends CustomPayload> getId() { return ID; }
}
