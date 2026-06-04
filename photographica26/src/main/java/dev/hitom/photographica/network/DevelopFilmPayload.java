package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S: developer tank was used. Server picks the first ExposedFilm from the
 * player's inventory, validates that the surrounding light level is 0,
 * and materialises each frame as a Photo item.
 */
public record DevelopFilmPayload() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<DevelopFilmPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "develop_film"));

	public static final StreamCodec<RegistryFriendlyByteBuf, DevelopFilmPayload> CODEC =
			StreamCodec.unit(new DevelopFilmPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() { return ID; }
}
