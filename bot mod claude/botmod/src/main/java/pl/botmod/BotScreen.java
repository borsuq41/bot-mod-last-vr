package pl.botmod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class BotScreen extends Screen {
    // zapamietane miedzy otwarciami okna
    private static String lastNick = "Bot_1";
    private static String lastAddress = "";

    private TextFieldWidget nickField;
    private TextFieldWidget addressField;
    private ButtonWidget actionButton;

    private int consoleX, consoleY, consoleW, consoleH;

    public BotScreen() {
        super(Text.literal("Bot Mod"));
    }

    @Override
    protected void init() {
        int w = Math.min(320, this.width - 20);
        int left = (this.width - w) / 2;
        int y = 28;

        if (lastAddress.isEmpty()) {
            ServerInfo info = MinecraftClient.getInstance().getCurrentServerEntry();
            if (info != null) lastAddress = info.address;
        }

        nickField = new TextFieldWidget(this.textRenderer, left, y + 10, w, 20, Text.literal("Nick"));
        nickField.setMaxLength(16);
        nickField.setText(lastNick);
        addDrawableChild(nickField);

        addressField = new TextFieldWidget(this.textRenderer, left, y + 46, w, 20, Text.literal("Adres"));
        addressField.setMaxLength(255);
        addressField.setText(lastAddress);
        addressField.setPlaceholder(Text.literal("np. nazwa.aternos.me"));
        addDrawableChild(addressField);

        actionButton = ButtonWidget.builder(Text.literal(""), btn -> onAction())
                .dimensions(left, y + 74, w, 20).build();
        addDrawableChild(actionButton);
        updateButton();

        consoleX = left;
        consoleY = y + 102;
        consoleW = w;
        consoleH = Math.max(40, this.height - consoleY - 12);
    }

    private void onAction() {
        lastNick = nickField.getText().trim();
        lastAddress = addressField.getText().trim();

        if (BotManager.isActive()) {
            BotManager.stop();
            return;
        }
        if (!lastNick.matches("[A-Za-z0-9_]{1,16}")) {
            BotManager.log("Zly nick (1-16 znakow: litery, cyfry, _).");
            return;
        }
        if (lastAddress.isEmpty()) {
            BotManager.log("Wpisz adres serwera.");
            return;
        }
        BotManager.start(lastNick, lastAddress);
    }

    private void updateButton() {
        if (actionButton != null) {
            actionButton.setMessage(Text.literal(BotManager.isActive() ? "Rozlacz bota" : "Wyslij bota"));
        }
    }

    @Override
    public void tick() {
        updateButton();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, "Nick bota", consoleX, nickField.getY() - 10, 0xA0A0A0);
        context.drawTextWithShadow(this.textRenderer, "Adres serwera", consoleX, addressField.getY() - 10, 0xA0A0A0);

        // konsola
        context.fill(consoleX, consoleY, consoleX + consoleW, consoleY + consoleH, 0xCC000000);
        context.drawBorder(consoleX, consoleY, consoleW, consoleH, 0xFF555555);

        int lineH = this.textRenderer.fontHeight + 1;
        int maxWidth = consoleW - 8;
        List<String> wrapped = new ArrayList<>();
        for (String line : BotManager.lines()) {
            String rest = line;
            while (!rest.isEmpty()) {
                String part = this.textRenderer.trimToWidth(rest, maxWidth);
                if (part.isEmpty()) break;
                wrapped.add(part);
                rest = rest.substring(part.length());
            }
        }
        int visible = (consoleH - 8) / lineH;
        int from = Math.max(0, wrapped.size() - visible);
        int y = consoleY + 4;
        for (int i = from; i < wrapped.size(); i++) {
            context.drawTextWithShadow(this.textRenderer, wrapped.get(i), consoleX + 4, y, 0x55FF55);
            y += lineH;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
