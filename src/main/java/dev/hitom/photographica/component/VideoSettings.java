package dev.hitom.photographica.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;

public record VideoSettings(float aperture) {

    public static final VideoSettings DEFAULT = new VideoSettings(2.8f);

    public static final Codec<VideoSettings> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.FLOAT.optionalFieldOf("aperture", 2.8f)
                               .forGetter(VideoSettings::aperture)
            ).apply(instance, VideoSettings::new));

    public static final PacketCodec<ByteBuf, VideoSettings> PACKET_CODEC = new PacketCodec<>() {
        @Override public VideoSettings decode(ByteBuf buf) { return new VideoSettings(buf.readFloat()); }
        @Override public void encode(ByteBuf buf, VideoSettings v) { buf.writeFloat(v.aperture()); }
    };

    public VideoSettings withAperture(float v) { return new VideoSettings(v); }
}
