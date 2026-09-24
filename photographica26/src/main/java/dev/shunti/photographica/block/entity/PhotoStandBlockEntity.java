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
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
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
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        if (photoData != null) {
            view.store("Photo", PhotoData.CODEC, photoData);
        }
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        photoData = view.read("Photo", PhotoData.CODEC).orElse(null);
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
