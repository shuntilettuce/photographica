package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** C2S: load a FilmRoll of the specified filmType from inventory into the held FilmCamera. */
public record LoadFilmPayload(int filmType) implements CustomPayload {
	public static final CustomPayload.Id<LoadFilmPayload> ID =
			new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "load_film"));

	public static final PacketCodec<RegistryByteBuf, LoadFilmPayload> CODEC = new PacketCodec<>() {
		@Override
		public LoadFilmPayload decode(RegistryByteBuf buf) {
			return new LoadFilmPayload(buf.readInt());
		}
		@Override
		public void encode(RegistryByteBuf buf, LoadFilmPayload v) {
			buf.writeInt(v.filmType);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() { return ID; }
}
