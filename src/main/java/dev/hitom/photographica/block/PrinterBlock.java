package dev.hitom.photographica.block;

import com.mojang.serialization.MapCodec;
import dev.hitom.photographica.block.entity.PrinterBlockEntity;
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

public class PrinterBlock extends BlockWithEntity {

    public static final MapCodec<PrinterBlock> CODEC = createCodec(PrinterBlock::new);

    public PrinterBlock(Settings settings) {
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
        return new PrinterBlockEntity(pos, state);
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
        if (be instanceof PrinterBlockEntity printer) {
            player.openHandledScreen(printer);
        }
        return ActionResult.CONSUME;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos,
                                 BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof PrinterBlockEntity printer) {
                ItemScatterer.spawn(world, pos, printer);
                world.updateComparators(pos, this);
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
