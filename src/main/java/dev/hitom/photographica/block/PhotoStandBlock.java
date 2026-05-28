package dev.hitom.photographica.block;

import com.mojang.serialization.MapCodec;
import dev.hitom.photographica.block.entity.PhotoStandBlockEntity;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.item.PhotoItem;
import dev.hitom.photographica.registry.ModItems;
import net.minecraft.block.Block;
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
import net.minecraft.state.property.EnumProperty;
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

public class PhotoStandBlock extends BlockWithEntity {

    public static final MapCodec<PhotoStandBlock> CODEC = createCodec(PhotoStandBlock::new);
    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

    // Bounding box: base (full depth) + upright panel (y up to 9/16).
    // SOUTH/NORTH: stand extends along z. EAST/WEST: stand extends along x.
    private static final VoxelShape SHAPE_NS = VoxelShapes.cuboid(1.0/16, 0, 2.0/16, 15.0/16, 9.0/16, 14.0/16);
    private static final VoxelShape SHAPE_EW = VoxelShapes.cuboid(2.0/16, 0, 1.0/16, 14.0/16, 9.0/16, 15.0/16);

    public PhotoStandBlock(Settings settings) {
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

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        Direction facing = state.get(FACING);
        return (facing == Direction.EAST || facing == Direction.WEST) ? SHAPE_EW : SHAPE_NS;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return getOutlineShape(state, world, pos, ctx);
    }

    @Override
    public @Nullable BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new PhotoStandBlockEntity(pos, state);
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
        if (!(be instanceof PhotoStandBlockEntity stand)) return ActionResult.PASS;

        ItemStack held = player.getMainHandStack();

        if (stand.getPhotoData() == null) {
            if (held.getItem() instanceof PhotoItem) {
                PhotoData data = held.get(ModDataComponents.PHOTO_DATA);
                if (data != null) {
                    stand.setPhoto(data);
                    if (!player.isCreative()) held.decrement(1);
                    world.playSound(null, pos, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.6f, 1.2f);
                    return ActionResult.CONSUME;
                }
            }
        } else {
            ItemStack photoStack = new ItemStack(ModItems.PHOTO);
            photoStack.set(ModDataComponents.PHOTO_DATA, stand.getPhotoData());
            stand.clearPhoto();
            world.playSound(null, pos, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.6f, 0.8f);
            if (!player.getInventory().insertStack(photoStack)) {
                player.dropItem(photoStack, false);
            }
            return ActionResult.CONSUME;
        }

        return ActionResult.PASS;
    }

    //? if >=1.21.11 {
    /*@Override
    protected void onStateReplaced(BlockState state, net.minecraft.server.world.ServerWorld world, BlockPos pos, boolean moved) {
        if (world.getBlockEntity(pos) instanceof PhotoStandBlockEntity stand) {
            PhotoData photo = stand.getPhotoData();
            if (photo != null) {
                ItemStack photoStack = new ItemStack(ModItems.PHOTO);
                photoStack.set(ModDataComponents.PHOTO_DATA, photo);
                Block.dropStack(world, pos, photoStack);
            }
        }
        super.onStateReplaced(state, world, pos, moved);
    }*/
    //?} else {
    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos,
                                 BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            if (world.getBlockEntity(pos) instanceof PhotoStandBlockEntity stand) {
                PhotoData photo = stand.getPhotoData();
                if (photo != null) {
                    ItemStack photoStack = new ItemStack(ModItems.PHOTO);
                    photoStack.set(ModDataComponents.PHOTO_DATA, photo);
                    Block.dropStack(world, pos, photoStack);
                }
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
    //?}
}
