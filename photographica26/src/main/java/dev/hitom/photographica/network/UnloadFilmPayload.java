package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: remove the film from the held FilmCamera and give it back as an ExposedFilm item. */
public record UnloadFilmPayload() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<UnloadFilmPayload> ID =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "unload_film"));

	public static final StreamCodec<RegistryFriendlyByteBuf, UnloadFilmPayload> CODEC =
			StreamCodec.unit(new UnloadFilmPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() { return ID; }
}
