package dev.hitom.photographica.block;

import com.mojang.serialization.MapCodec;
import dev.hitom.photographica.block.entity.PhotoStandBlockEntity;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.item.PhotoItem;
import dev.hitom.photographica.registry.ModItems;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import org.jetbrains.annotations.Nullable;

public class PhotoStandBlock extends BaseEntityBlock {

    public static final MapCodec<PhotoStandBlock> CODEC = simpleCodec(PhotoStandBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    // Bounding box: base (full depth) + upright panel (y up to 9/16).
    private static final VoxelShape SHAPE_NS = Block.box(1, 0, 2, 15, 9, 14);
    private static final VoxelShape SHAPE_EW = Block.box(2, 0, 1, 14, 9, 15);

    public PhotoStandBlock(BlockBehaviour.Properties settings) {
        super(settings);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext ctx) {
        Direction facing = state.getValue(FACING);
        return (facing == Direction.EAST || facing == Direction.WEST) ? SHAPE_EW : SHAPE_NS;
    }

    protected VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext ctx) {
        return getShape(state, world, pos, ctx);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PhotoStandBlockEntity(pos, state);
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
        if (!(be instanceof PhotoStandBlockEntity stand)) return InteractionResult.PASS;

        ItemStack held = player.getMainHandItem();

        if (stand.getPhotoData() == null) {
            if (held.getItem() instanceof PhotoItem) {
                PhotoData data = held.get(ModDataComponents.PHOTO_DATA);
                if (data != null) {
                    stand.setPhoto(data);
                    if (!player.isCreative()) held.shrink(1);
                    world.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6f, 1.2f);
                    return InteractionResult.CONSUME;
                }
            }
        } else {
            ItemStack photoStack = new ItemStack(ModItems.PHOTO);
            photoStack.set(ModDataComponents.PHOTO_DATA, stand.getPhotoData());
            stand.clearPhoto();
            world.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6f, 0.8f);
            if (!player.getInventory().add(photoStack)) {
                player.drop(photoStack, false);
            }
            return InteractionResult.CONSUME;
        }

        return InteractionResult.PASS;
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel world, BlockPos pos, boolean moved) {
        if (world.getBlockEntity(pos) instanceof PhotoStandBlockEntity stand) {
            PhotoData photo = stand.getPhotoData();
            if (photo != null) {
                ItemStack photoStack = new ItemStack(ModItems.PHOTO);
                photoStack.set(ModDataComponents.PHOTO_DATA, photo);
                Block.popResource(world, pos, photoStack);
            }
        }
        super.affectNeighborsAfterRemoval(state, world, pos, moved);
    }
}
