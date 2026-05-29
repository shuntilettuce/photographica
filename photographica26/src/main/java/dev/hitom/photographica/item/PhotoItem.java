package dev.hitom.photographica.item;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

public class PhotoItem extends Item {
	/** Wired by client init. Opens the photo viewer for the given data. */
	public static Consumer<PhotoData> clientOpenViewer = data -> {};

	public PhotoItem(Properties settings) {
		super(settings.stacksTo(64));
	}

	@Override
	public InteractionResult use(Level world, Player user, InteractionHand hand) {
		ItemStack stack = user.getItemInHand(hand);
		PhotoData data = stack.get(ModDataComponents.PHOTO_DATA);
		if (data == null) {
			return InteractionResult.PASS;
		}
		if (world.isClientSide()) {
			clientOpenViewer.accept(data);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public Component getName(ItemStack stack) {
		PhotoData data = stack.get(ModDataComponents.PHOTO_DATA);
		if (data != null && data.cameraAtCapture().isFilm()) {
			return Component.translatable(getDescriptionId()).withStyle(ChatFormatting.GOLD);
		}
		return super.getName(stack);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context,
	                            net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
	                            Consumer<Component> tooltipSink, TooltipFlag type) {
		PhotoData data = stack.get(ModDataComponents.PHOTO_DATA);
		if (data == null) {
			tooltipSink.accept(Component.literal("(未現像)").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
			return;
		}
		boolean isFilm = data.cameraAtCapture().isFilm();
		MutableComponent badge = isFilm
				? Component.literal("[ フィルム写真 ]").withStyle(ChatFormatting.GOLD)
				: Component.literal("[ デジタル写真 ]").withStyle(ChatFormatting.AQUA);
		tooltipSink.accept(badge);
		if (data.fogged()) {
			tooltipSink.accept(Component.literal("⚠ 光被り").withStyle(ChatFormatting.RED));
		}
		tooltipSink.accept(Component.literal("撮影: " + data.photographer()).withStyle(ChatFormatting.GRAY));
		tooltipSink.accept(Component.literal(String.format("F%.1f  ISO%d  %dmm",
				data.cameraAtCapture().aperture(),
				data.cameraAtCapture().iso(),
				data.cameraAtCapture().focalLengthMm()))
				.withStyle(ChatFormatting.DARK_GRAY));
		tooltipSink.accept(Component.literal(String.format("[%s] (%d, %d, %d)",
				data.dimension(), data.x(), data.y(), data.z()))
				.withStyle(ChatFormatting.DARK_GRAY));
		tooltipSink.accept(Component.literal("ID: " + data.id().toString().substring(0, 8))
				.withStyle(ChatFormatting.DARK_GRAY));
	}
}
