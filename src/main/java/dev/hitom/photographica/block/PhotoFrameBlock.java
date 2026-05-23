package dev.hitom.photographica.block;

import com.mojang.serialization.MapCodec;
import dev.hitom.photographica.block.entity.PhotoFrameBlockEntity;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.item.PhotoItem;
import dev.hitom.photographica.registry.ModItems;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class PhotoFrameBlock extends BlockWithEntity {

    public static final MapCodec<PhotoFrameBlock> CODEC = createCodec(PhotoFrameBlock::new);
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    // Thin 2-pixel collision/outline shapes matching the model geometry, one per facing direction.
    private static final VoxelShape SHAPE_SOUTH = VoxelShapes.cuboid(0, 0, 0,      1, 1, 2.0/16);
    private static final VoxelShape SHAPE_NORTH = VoxelShapes.cuboid(0, 0, 14.0/16, 1, 1, 1);
    private static final VoxelShape SHAPE_WEST  = VoxelShapes.cuboid(0, 0, 0,      2.0/16, 1, 1);
    private static final VoxelShape SHAPE_EAST  = VoxelShapes.cuboid(14.0/16, 0, 0, 1, 1, 1);

    public PhotoFrameBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
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
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    private VoxelShape shapeFor(BlockState state) {
        return switch (state.get(FACING)) {
            case NORTH -> SHAPE_NORTH;
            case WEST  -> SHAPE_WEST;
            case EAST  -> SHAPE_EAST;
            default    -> SHAPE_SOUTH;
        };
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return shapeFor(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return shapeFor(state);
    }

    @Override
    public @Nullable BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new PhotoFrameBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            World world, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                               PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof PhotoFrameBlockEntity frame)) return ActionResult.PASS;

        ItemStack held = player.getMainHandStack();

        if (frame.getPhotoData() == null) {
            // Try to place a photo from the player's hand.
            if (held.getItem() instanceof PhotoItem) {
                PhotoData data = held.get(ModDataComponents.PHOTO_DATA);
                if (data != null) {
                    frame.setPhoto(data);
                    if (!player.isCreative()) held.decrement(1);
                    world.playSound(null, pos, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.6f, 1.2f);
                    return ActionResult.CONSUME;
                }
            }
        } else {
            // Remove the photo and give it back (or drop it).
            ItemStack photoStack = new ItemStack(ModItems.PHOTO);
            photoStack.set(ModDataComponents.PHOTO_DATA, frame.getPhotoData());
            frame.clearPhoto();
            world.playSound(null, pos, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.6f, 0.8f);
            if (!player.getInventory().insertStack(photoStack)) {
                player.dropItem(photoStack, false);
            }
            return ActionResult.CONSUME;
        }

        return ActionResult.PASS;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos,
                                 BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            if (world.getBlockEntity(pos) instanceof PhotoFrameBlockEntity frame) {
                PhotoData photo = frame.getPhotoData();
                if (photo != null) {
                    ItemStack photoStack = new ItemStack(ModItems.PHOTO);
                    photoStack.set(ModDataComponents.PHOTO_DATA, photo);
                    Block.dropStack(world, pos, photoStack);
                }
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
