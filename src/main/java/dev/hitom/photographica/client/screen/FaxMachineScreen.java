package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.network.SendFaxPayload;
import dev.hitom.photographica.screen.FaxMachineScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class FaxMachineScreen extends HandledScreen<FaxMachineScreenHandler> {

    private TextFieldWidget numberField;

    public FaxMachineScreen(FaxMachineScreenHandler handler, PlayerInventory playerInventory, Text title) {
        super(handler, playerInventory, title);
        this.backgroundWidth = 176;
        this.backgroundHeight = 176;
    }

    @Override
    protected void init() {
        super.init();
        int x = this.x, y = this.y;

        numberField = new TextFieldWidget(this.textRenderer, x + 6, y + 58, 80, 14, Text.literal("宛先番号"));
        numberField.setMaxLength(4);
        // Digits only — a fax number here is purely a lookup key, not a real quantity, but
        // typing letters would just get rejected server-side anyway; filtering client-side
        // saves the round trip.
        numberField.setTextPredicate(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        addDrawableChild(numberField);

        addDrawableChild(SafelightButton.primary(x + 90, y + 58, 80, Text.literal("SEND"),
                b -> sendFax()));
    }

    private void sendFax() {
        if (numberField.getText().isEmpty()) return;
        int target;
        try {
            target = Integer.parseInt(numberField.getText());
        } catch (NumberFormatException e) {
            return;
        }
        ClientPlayNetworking.send(new SendFaxPayload(this.handler.pos, target));
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        int x = this.x, y = this.y, w = backgroundWidth, h = backgroundHeight;

        GuiHelper.drawPanel(ctx, x, y, w, h);
        GuiHelper.drawRule(ctx, x + 6, y + 14, w - 12);

        // LCD showing this machine's own number, top-right.
        GuiHelper.drawLcd(ctx, x + w - 46, y + 3, 40, 9);

        GuiHelper.drawPlayerInventory(ctx, x, y, 94, 152);

        GuiHelper.drawSlot(ctx, x + 44, y + 35);
        GuiHelper.drawSlot(ctx, x + 80, y + 35);

        GuiHelper.drawNameplate(ctx, x + 6, y + 80, 164);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(ctx, mouseX, mouseY);
    }

    @Override
    protected void drawForeground(DrawContext ctx, int mouseX, int mouseY) {
        ctx.fill(3, 5, 6, 8, GuiHelper.SAFELIGHT);
        ctx.drawText(textRenderer, Text.literal("FAX"), 9, 5, GuiHelper.CREAM, false);

        ctx.drawText(textRenderer, Text.literal("#" + this.handler.machineNumber),
                backgroundWidth - 45, 4, GuiHelper.EMBER, false);

        ctx.drawText(textRenderer, Text.literal("SEND"),  38, 24, GuiHelper.BRASS_BRIGHT, false);
        ctx.drawText(textRenderer, Text.literal("INBOX"), 74, 24, GuiHelper.BRASS_BRIGHT, false);

        ctx.drawText(textRenderer, Text.literal("宛先"), 8, 48, GuiHelper.CREAM_DIM, false);

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("F-1 · REMOTE PRINT"),
                backgroundWidth / 2, 82, GuiHelper.CREAM_DIM);
    }
}
