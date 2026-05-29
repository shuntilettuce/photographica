package dev.hitom.photographica.block;

import com.mojang.serialization.MapCodec;
import dev.hitom.photographica.block.entity.PhotoFrameBlockEntity;
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
import net.minecraft.world.level.block.SoundType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import org.jetbrains.annotations.Nullable;

public class PhotoFrameBlock extends BaseEntityBlock {

    public static final MapCodec<PhotoFrameBlock> CODEC = simpleCodec(PhotoFrameBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    // 3:2 landscape shapes (12×8 face, 2px thick) matching the model geometry, one per facing direction.
    private static final VoxelShape SHAPE_SOUTH = Block.box(2, 4, 0,       14, 12, 2);
    private static final VoxelShape SHAPE_NORTH = Block.box(2, 4, 14, 14, 12, 16);
    private static final VoxelShape SHAPE_WEST  = Block.box(14, 4, 2, 16,  12, 14);
    private static final VoxelShape SHAPE_EAST  = Block.box(0,  4, 2, 2,   12, 14);

    public PhotoFrameBlock(BlockBehaviour.Properties settings) {
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

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    private VoxelShape shapeFor(BlockState state) {
        return switch (state.getValue(FACING)) {
            case NORTH -> SHAPE_NORTH;
            case WEST  -> SHAPE_WEST;
            case EAST  -> SHAPE_EAST;
            default    -> SHAPE_SOUTH;
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext ctx) {
        return shapeFor(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext ctx) {
        return shapeFor(state);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PhotoFrameBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            net.minecraft.world.level.Level world, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level world,
                                               BlockPos pos, Player player, BlockHitResult hit) {
        if (world.isClientSide()) return InteractionResult.SUCCESS;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof PhotoFrameBlockEntity frame)) return InteractionResult.PASS;

        ItemStack held = player.getMainHandItem();

        if (frame.getPhotoData() == null) {
            // Try to place a photo from the player's hand.
            if (held.getItem() instanceof PhotoItem) {
                PhotoData data = held.get(ModDataComponents.PHOTO_DATA);
                if (data != null) {
                    frame.setPhoto(data);
                    if (!player.isCreative()) held.shrink(1);
                    world.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6f, 1.2f);
                    return InteractionResult.CONSUME;
                }
            }
        } else {
            // Remove the photo and give it back (or drop it).
            ItemStack photoStack = new ItemStack(ModItems.PHOTO);
            photoStack.set(ModDataComponents.PHOTO_DATA, frame.getPhotoData());
            frame.clearPhoto();
            world.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6f, 0.8f);
            if (!player.getInventory().add(photoStack)) {
                player.drop(photoStack, false);
            }
            return InteractionResult.CONSUME;
        }

        return InteractionResult.PASS;
    }

    @Override
    protected void onRemove(BlockState state, ServerLevel world, BlockPos pos, boolean moved) {
        if (world.getBlockEntity(pos) instanceof PhotoFrameBlockEntity frame) {
            PhotoData photo = frame.getPhotoData();
            if (photo != null) {
                ItemStack photoStack = new ItemStack(ModItems.PHOTO);
                photoStack.set(ModDataComponents.PHOTO_DATA, photo);
                Block.popResource(world, pos, photoStack);
            }
        }
        super.onRemove(state, world, pos, moved);
    }
}
