package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.network.LoadFilmPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;

/**
 * Small modal that lists available film types vertically so the player can
 * choose which roll to load. Opened by FilmCameraScreen when multiple types
 * are present in the inventory.
 */
@Environment(EnvType.CLIENT)
public class FilmPickerScreen extends Screen {

    private static final int PANEL_W  = 240;
    private static final int HEADER_H = 26;  // nameplate + rule + breathing room
    private static final int ROW_H    = 32;  // slot height; button is 20px, leaving 12px gap
    private static final int PAD      = 10;  // left/right and bottom padding

    private final Screen parent;
    // filmType → total count in inventory
    private final Map<Integer, Integer> available;
    private final List<Integer> types;

    public FilmPickerScreen(Screen parent, Map<Integer, Integer> available) {
        super(Component.literal("フィルム選択"));
        this.parent    = parent;
        this.available = available;
        this.types     = List.copyOf(available.keySet());
    }

    private int panelH() {
        return HEADER_H + types.size() * ROW_H + 6 + ROW_H + PAD;
    }

    @Override
    protected void init() {
        int px = width  / 2 - PANEL_W / 2;
        int py = height / 2 - panelH() / 2;
        int bx = px + PAD;
        int bw = PANEL_W - PAD * 2;

        int y = py + HEADER_H;
        for (int ft : types) {
            int count = available.get(ft);
            String iso   = "ISO" + FilmKind.isoOf(ft);
            String shots = FilmKind.defaultExposures(ft) + "枚";
            String label = FilmKind.displayName(ft) + "  " + iso + "  " + shots + "  ×" + count;
            int finalFt = ft;
            addRenderableWidget(SafelightButton.primary(bx, y, bw,
                    Component.literal(label),
                    b -> {
                        ClientPlayNetworking.send(new LoadFilmPayload(finalFt));
                        minecraft.setScreen(null);
                    }));
            y += ROW_H;
        }

        y += 6;
        addRenderableWidget(SafelightButton.ghost(bx, y, bw,
                Component.literal("キャンセル"),
                b -> minecraft.setScreen(parent)));
    }

    @Override
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {}

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        int px = width  / 2 - PANEL_W / 2;
        int py = height / 2 - panelH() / 2;

        // Dim the parent screen behind the panel
        ctx.fill(0, 0, width, height, 0x88000000);

        GuiHelper.drawPanel(ctx, px, py, PANEL_W, panelH());
        GuiHelper.drawNameplate(ctx, px + 6, py + 5, PANEL_W - 12);
        GuiHelper.drawRule(ctx, px + 6, py + 17, PANEL_W - 12);
        ctx.drawCenteredString(font,
                Component.literal("フィルム選択"), width / 2, py + 6, GuiHelper.CREAM);

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
