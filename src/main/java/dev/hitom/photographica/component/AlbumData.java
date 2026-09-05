package dev.hitom.photographica.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A physical album's contents: real printed {@link net.minecraft.item.ItemStack}s (Photo
 * items), not the flattened metadata {@link SdCardData} uses. The distinction matters — an SD
 * card is digital storage that MAKES a physical photo item on demand, but an album is a
 * container for photos that already exist as objects. Pull one out and you get back the exact
 * print you put in, film grain, ID and all.
 */
public record AlbumData(List<ItemStack> photos, int capacity) {

    public static final int DEFAULT_CAPACITY = 27; // one GUI's worth: 3 rows of 9

    public static final AlbumData EMPTY = new AlbumData(List.of(), DEFAULT_CAPACITY);

    public static final Codec<AlbumData> CODEC = RecordCodecBuilder.create(i -> i.group(
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("photos").forGetter(AlbumData::photos),
            Codec.INT.optionalFieldOf("capacity", DEFAULT_CAPACITY).forGetter(AlbumData::capacity)
    ).apply(i, AlbumData::new));

    public static final PacketCodec<RegistryByteBuf, AlbumData> PACKET_CODEC = new PacketCodec<>() {
        @Override
        public AlbumData decode(RegistryByteBuf buf) {
            int capacity = buf.readInt();
            int n = buf.readInt();
            List<ItemStack> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(ItemStack.OPTIONAL_PACKET_CODEC.decode(buf));
            return new AlbumData(Collections.unmodifiableList(list), capacity);
        }

        @Override
        public void encode(RegistryByteBuf buf, AlbumData v) {
            buf.writeInt(v.capacity);
            buf.writeInt(v.photos.size());
            for (ItemStack s : v.photos) ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, s);
        }
    };

    public boolean isEmpty() { return photos.stream().allMatch(ItemStack::isEmpty); }
    public int filledSlots() { return (int) photos.stream().filter(s -> !s.isEmpty()).count(); }
}
