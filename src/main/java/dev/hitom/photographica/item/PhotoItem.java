package dev.hitom.photographica.item;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
//? if <1.21.4 {
import net.minecraft.util.TypedActionResult;
//?}
import net.minecraft.world.World;

import java.util.List;
import java.util.function.Consumer;

public class PhotoItem extends Item {
	/** Wired by client init. Opens the photo viewer for the given data. */
	public static Consumer<PhotoData> clientOpenViewer = data -> {};

	public PhotoItem(Settings settings) {
		super(settings.maxCount(64));
	}

	//? if >=1.21.4 {
	/*@Override
	public ActionResult use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		PhotoData data = stack.get(ModDataComponents.PHOTO_DATA);
		if (data == null) {
			return ActionResult.PASS;
		}
		if (world.isClient()) {
			clientOpenViewer.accept(data);
		}
		return ActionResult.SUCCESS;
	}*/
	//?} else {
	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		PhotoData data = stack.get(ModDataComponents.PHOTO_DATA);
		if (data == null) {
			return TypedActionResult.pass(stack);
		}
		if (world.isClient()) {
			clientOpenViewer.accept(data);
		}
		return TypedActionResult.success(stack, world.isClient());
	}
	//?}

	@Override
	public Text getName(ItemStack stack) {
		PhotoData data = stack.get(ModDataComponents.PHOTO_DATA);
		if (data != null && data.cameraAtCapture().isFilm()) {
			//? if >=1.21.4 {
			/*return Text.translatable(getTranslationKey()).formatted(Formatting.GOLD);*/
			//?} else {
			return Text.translatable(getTranslationKey(stack)).formatted(Formatting.GOLD);
			//?}
		}
		return super.getName(stack);
	}

	//? if >=1.21.11 {
	/*@Override
	public void appendTooltip(ItemStack stack, Item.TooltipContext context, net.minecraft.component.type.TooltipDisplayComponent tooltipDisplay, java.util.function.Consumer<Text> tooltipSink, TooltipType type) {
		PhotoData data = stack.get(ModDataComponents.PHOTO_DATA);
		if (data == null) {
			tooltipSink.accept(Text.literal("(未現像)").formatted(Formatting.GRAY, Formatting.ITALIC));
			return;
		}
		boolean isFilm = data.cameraAtCapture().isFilm();
		MutableText badge = isFilm
				? Text.literal("[ フィルム写真 ]").formatted(Formatting.GOLD)
				: Text.literal("[ デジタル写真 ]").formatted(Formatting.AQUA);
		tooltipSink.accept(badge);
		if (data.fogged()) {
			tooltipSink.accept(Text.literal("⚠ 光被り").formatted(Formatting.RED));
		}
		tooltipSink.accept(Text.literal("撮影: " + data.photographer()).formatted(Formatting.GRAY));
		tooltipSink.accept(Text.literal(String.format("F%.1f  ISO%d  %dmm",
				data.cameraAtCapture().aperture(),
				data.cameraAtCapture().iso(),
				data.cameraAtCapture().focalLengthMm()))
				.formatted(Formatting.DARK_GRAY));
		tooltipSink.accept(Text.literal(String.format("[%s] (%d, %d, %d)",
				data.dimension(), data.x(), data.y(), data.z()))
				.formatted(Formatting.DARK_GRAY));
		tooltipSink.accept(Text.literal("ID: " + data.id().toString().substring(0, 8))
				.formatted(Formatting.DARK_GRAY));
	}*/
	//?} else {
	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		PhotoData data = stack.get(ModDataComponents.PHOTO_DATA);
		if (data == null) {
			tooltip.add(Text.literal("(未現像)").formatted(Formatting.GRAY, Formatting.ITALIC));
			return;
		}
		boolean isFilm = data.cameraAtCapture().isFilm();
		MutableText badge = isFilm
				? Text.literal("[ フィルム写真 ]").formatted(Formatting.GOLD)
				: Text.literal("[ デジタル写真 ]").formatted(Formatting.AQUA);
		tooltip.add(badge);
		if (data.fogged()) {
			tooltip.add(Text.literal("⚠ 光被り").formatted(Formatting.RED));
		}
		tooltip.add(Text.literal("撮影: " + data.photographer()).formatted(Formatting.GRAY));
		tooltip.add(Text.literal(String.format("F%.1f  ISO%d  %dmm",
				data.cameraAtCapture().aperture(),
				data.cameraAtCapture().iso(),
				data.cameraAtCapture().focalLengthMm()))
				.formatted(Formatting.DARK_GRAY));
		tooltip.add(Text.literal(String.format("[%s] (%d, %d, %d)",
				data.dimension(), data.x(), data.y(), data.z()))
				.formatted(Formatting.DARK_GRAY));
		tooltip.add(Text.literal("ID: " + data.id().toString().substring(0, 8))
				.formatted(Formatting.DARK_GRAY));
	}
	//?}
}
