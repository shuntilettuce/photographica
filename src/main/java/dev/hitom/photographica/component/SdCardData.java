package dev.hitom.photographica.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SdCardData(List<PhotoData> photos, int capacity) {
    public static final int DEFAULT_CAPACITY = 64;
    public static final SdCardData EMPTY = new SdCardData(List.of(), DEFAULT_CAPACITY);

    public static final Codec<SdCardData> CODEC = RecordCodecBuilder.create(i -> i.group(
            PhotoData.CODEC.listOf().fieldOf("photos").forGetter(SdCardData::photos),
            Codec.INT.optionalFieldOf("capacity", DEFAULT_CAPACITY).forGetter(SdCardData::capacity)
    ).apply(i, SdCardData::new));

    public static final PacketCodec<ByteBuf, SdCardData> PACKET_CODEC = new PacketCodec<>() {
        @Override
        public SdCardData decode(ByteBuf buf) {
            int capacity = buf.readInt();
            int n = buf.readInt();
            List<PhotoData> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(PhotoData.PACKET_CODEC.decode(buf));
            return new SdCardData(Collections.unmodifiableList(list), capacity);
        }

        @Override
        public void encode(ByteBuf buf, SdCardData v) {
            buf.writeInt(v.capacity);
            buf.writeInt(v.photos.size());
            for (PhotoData p : v.photos) PhotoData.PACKET_CODEC.encode(buf, p);
        }
    };

    public boolean isFull() { return photos.size() >= capacity; }
    public int remaining() { return capacity - photos.size(); }
    public boolean isEmpty() { return photos.isEmpty(); }

    public SdCardData withPhoto(PhotoData photo) {
        List<PhotoData> newList = new ArrayList<>(photos);
        newList.add(photo);
        return new SdCardData(Collections.unmodifiableList(newList), capacity);
    }

    public SdCardData withoutPhotos() {
        return new SdCardData(List.of(), capacity);
    }
}
