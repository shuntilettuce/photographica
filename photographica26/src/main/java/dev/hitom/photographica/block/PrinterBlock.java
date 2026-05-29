package dev.hitom.photographica.block;

import com.mojang.serialization.MapCodec;
import dev.hitom.photographica.block.entity.PrinterBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Containers;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import org.jetbrains.annotations.Nullable;

public class PrinterBlock extends BaseEntityBlock {

    public static final MapCodec<PrinterBlock> CODEC = simpleCodec(PrinterBlock::new);

    public PrinterBlock(BlockBehaviour.Properties settings) {
        super(settings);
    }

    @Override
    public MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PrinterBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            net.minecraft.world.level.Level world, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    protected InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level world,
                                               BlockPos pos, Player player, BlockHitResult hit) {
        if (world.isClientSide()) return InteractionResult.SUCCESS;
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof PrinterBlockEntity printer) {
            player.openMenu(printer);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel world, BlockPos pos, boolean moved) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof PrinterBlockEntity printer) {
            Containers.dropContents(world, pos, printer);
            world.updateNeighbourForOutputSignal(pos, this);
        }
        super.affectNeighborsAfterRemoval(state, world, pos, moved);
    }
}
