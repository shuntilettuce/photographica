package dev.hitom.photographica.block.entity;

import com.mojang.serialization.DataResult;
import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.registry.ModBlockEntities;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.storage.WriteView;
import net.minecraft.storage.ReadView;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

public class PhotoFrameBlockEntity extends BlockEntity {

    @Nullable
    private PhotoData photoData = null;

    public PhotoFrameBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PHOTO_FRAME, pos, state);
    }

    public @Nullable PhotoData getPhotoData() {
        return photoData;
    }

    public void setPhoto(PhotoData data) {
        this.photoData = data;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    public void clearPhoto() {
        this.photoData = null;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        if (photoData != null) {
            DataResult<Tag> result = PhotoData.CODEC.encodeStart(NbtOps.INSTANCE, photoData);
            result.result().ifPresent(el -> view.store("Photo", el));
        }
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        Tag photoTag = view.read("Photo", Tag.class).orElse(null);
        if (photoTag != null) {
            PhotoData.CODEC.parse(NbtOps.INSTANCE, photoTag)
                    .result()
                    .ifPresent(d -> photoData = d);
        } else {
            photoData = null;
        }
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider lookup) {
        return saveCustomOnly(lookup);
    }
}
