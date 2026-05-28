package dev.hitom.photographica.block;

import com.mojang.serialization.MapCodec;
import dev.hitom.photographica.block.entity.EnlargerBlockEntity;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class EnlargerBlock extends BlockWithEntity {

    public static final MapCodec<EnlargerBlock> CODEC = createCodec(EnlargerBlock::new);

    public EnlargerBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new EnlargerBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            World world, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                               PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) return ActionResult.SUCCESS;
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof EnlargerBlockEntity enlarger) {
            player.openHandledScreen(enlarger);
        }
        return ActionResult.CONSUME;
    }

    //? if >=1.21.11 {
    /*@Override
    protected void onStateReplaced(BlockState state, net.minecraft.server.world.ServerWorld world, BlockPos pos, boolean moved) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof EnlargerBlockEntity enlarger) {
            ItemScatterer.spawn(world, pos, enlarger);
            world.updateComparators(pos, this);
        }
        super.onStateReplaced(state, world, pos, moved);
    }*/
    //?} else {
    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos,
                                 BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof EnlargerBlockEntity enlarger) {
                ItemScatterer.spawn(world, pos, enlarger);
                world.updateComparators(pos, this);
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
    //?}
}
