package dev.hitom.photographica.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Camcorder state held on the ItemStack. {@code motionBlur}: 0 = off, 1 = light, 2 = strong. */
public record VideoSettings(float aperture, int fps, int motionBlur) {

    public static final VideoSettings DEFAULT = new VideoSettings(2.8f, 24, 1);

    public static final Codec<VideoSettings> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.FLOAT.optionalFieldOf("aperture", 2.8f)
                               .forGetter(VideoSettings::aperture),
                    Codec.INT.optionalFieldOf("fps", 24)
                             .forGetter(VideoSettings::fps),
                    Codec.INT.optionalFieldOf("motion_blur", 1)
                             .forGetter(VideoSettings::motionBlur)
            ).apply(instance, VideoSettings::new));

    public static final StreamCodec<ByteBuf, VideoSettings> PACKET_CODEC = new StreamCodec<>() {
        @Override
        public VideoSettings decode(ByteBuf buf) {
            return new VideoSettings(buf.readFloat(), buf.readInt(), buf.readInt());
        }
        @Override
        public void encode(ByteBuf buf, VideoSettings v) {
            buf.writeFloat(v.aperture());
            buf.writeInt(v.fps());
            buf.writeInt(v.motionBlur());
        }
    };

    public VideoSettings withAperture(float v)  { return new VideoSettings(v, fps, motionBlur); }
    public VideoSettings withFps(int v)         { return new VideoSettings(aperture, v, motionBlur); }
    public VideoSettings withMotionBlur(int v)  { return new VideoSettings(aperture, fps, Math.max(0, Math.min(2, v))); }
}
