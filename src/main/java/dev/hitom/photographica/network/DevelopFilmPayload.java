package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: developer tank was used. Server picks the first ExposedFilm from the
 * player's inventory, validates that the surrounding light level is 0,
 * and materialises each frame as a Photo item.
 */
public record DevelopFilmPayload() implements CustomPayload {
	public static final CustomPayload.Id<DevelopFilmPayload> ID =
			new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "develop_film"));

	public static final PacketCodec<RegistryByteBuf, DevelopFilmPayload> CODEC =
			PacketCodec.unit(new DevelopFilmPayload());

	@Override
	public Id<? extends CustomPayload> getId() { return ID; }
}
