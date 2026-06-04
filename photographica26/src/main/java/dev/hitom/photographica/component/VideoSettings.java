package dev.hitom.photographica.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record VideoSettings(float aperture, int fps) {

    public static final VideoSettings DEFAULT = new VideoSettings(2.8f, 24);

    public static final Codec<VideoSettings> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.FLOAT.optionalFieldOf("aperture", 2.8f)
                               .forGetter(VideoSettings::aperture),
                    Codec.INT.optionalFieldOf("fps", 24)
                             .forGetter(VideoSettings::fps)
            ).apply(instance, VideoSettings::new));

    public static final StreamCodec<ByteBuf, VideoSettings> PACKET_CODEC = new StreamCodec<>() {
        @Override
        public VideoSettings decode(ByteBuf buf) {
            return new VideoSettings(buf.readFloat(), buf.readInt());
        }
        @Override
        public void encode(ByteBuf buf, VideoSettings v) {
            buf.writeFloat(v.aperture());
            buf.writeInt(v.fps());
        }
    };

    public VideoSettings withAperture(float v) { return new VideoSettings(v, fps); }
    public VideoSettings withFps(int v)        { return new VideoSettings(aperture, v); }
}
