package dev.hitom.photographica.block.entity;

import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.registry.ModBlockEntities;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
//? if >=1.21.11 {
/*import net.minecraft.storage.WriteView;
import net.minecraft.storage.ReadView;*/
//?} else {
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
//?}
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

public class PhotoStandBlockEntity extends BlockEntity {

    @Nullable
    private PhotoData photoData = null;

    public PhotoStandBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PHOTO_STAND, pos, state);
    }

    public @Nullable PhotoData getPhotoData() { return photoData; }

    public void setPhoto(PhotoData data) {
        this.photoData = data;
        markDirty();
        if (world != null && !world.isClient) {
            world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
        }
    }

    public void clearPhoto() {
        this.photoData = null;
        markDirty();
        if (world != null && !world.isClient) {
            world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
        }
    }

    //? if >=1.21.11 {
    /*@Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        if (photoData != null) {
            view.put("Photo", PhotoData.CODEC, photoData);
        }
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        photoData = view.read("Photo", PhotoData.CODEC).orElse(null);
    }*/
    //?} else {
    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        if (photoData != null) {
            DataResult<NbtElement> result = PhotoData.CODEC.encodeStart(NbtOps.INSTANCE, photoData);
            result.result().ifPresent(el -> nbt.put("Photo", el));
        }
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        if (nbt.contains("Photo")) {
            PhotoData.CODEC.parse(NbtOps.INSTANCE, nbt.get("Photo"))
                    .result()
                    .ifPresent(d -> photoData = d);
        } else {
            photoData = null;
        }
    }
    //?}

    @Override
    public @Nullable Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        return createNbt(lookup);
    }
}
