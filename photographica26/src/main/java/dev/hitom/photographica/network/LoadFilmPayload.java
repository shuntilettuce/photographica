package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: load a FilmRoll of the specified filmType from inventory into the held FilmCamera. */
public record LoadFilmPayload(int filmType) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<LoadFilmPayload> ID =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "load_film"));

	public static final StreamCodec<RegistryFriendlyByteBuf, LoadFilmPayload> CODEC = new StreamCodec<>() {
		@Override
		public LoadFilmPayload decode(RegistryFriendlyByteBuf buf) {
			return new LoadFilmPayload(buf.readInt());
		}
		@Override
		public void encode(RegistryFriendlyByteBuf buf, LoadFilmPayload v) {
			buf.writeInt(v.filmType);
		}
	};

	@Override
	public Type<? extends CustomPacketPayload> type() { return ID; }
}
