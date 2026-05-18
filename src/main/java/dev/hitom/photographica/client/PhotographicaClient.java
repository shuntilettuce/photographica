package dev.hitom.photographica.client;

import dev.hitom.photographica.client.hud.ViewfinderHud;
import dev.hitom.photographica.client.screen.CameraScreen;
import dev.hitom.photographica.client.screen.PhotoViewerScreen;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.PhotoItem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;

public class PhotographicaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CameraItem.clientOpenScreen = stack ->
				MinecraftClient.getInstance().setScreen(new CameraScreen(stack));
		CameraItem.clientTakePhoto = PhotoCapture::take;
		PhotoItem.clientOpenViewer = data ->
				MinecraftClient.getInstance().setScreen(new PhotoViewerScreen(data));

		HudRenderCallback.EVENT.register(ViewfinderHud::render);
	}
}
