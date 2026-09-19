package wynnvoicechat.mod.screen;

import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import wynnvoicechat.mod.VoiceMod;

/**
 * Consent notice and the everyone-audience / guild-channel rules, drawn like Wynncraft's own panels: the title in the
 * server pack's {@code banner/box} glyph font, the body in its {@code language/wynncraft} font. Nothing risky starts
 * without an explicit accept: closing declines.
 */
public final class ConsentScreen extends Screen {
    private static final int ACCEPT_DELAY_TICKS = 60;
    private static final int BODY_WIDTH = 360;
    private static final int TITLE_HEIGHT = 36;
    private static final int BUTTON_GAP = 14;
    private static final int BUTTON_HEIGHT = 20;
    private static final int PANEL_PADDING = 12;
    private static final int PANEL = 0xAA000000;
    private static final int ACCENT = 0xFFACFAFA;
    private static final Style WYNNCRAFT_FONT = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.withDefaultNamespace("language/wynncraft")));
    private static final ChatFormatting[] CONSENT_COLOURS = {ChatFormatting.GOLD, ChatFormatting.WHITE, ChatFormatting.RED, ChatFormatting.WHITE, ChatFormatting.GREEN};
    private static final ChatFormatting[] EVERYONE_COLOURS = {ChatFormatting.WHITE, ChatFormatting.GREEN, ChatFormatting.RED};
    private static final ChatFormatting[] GUILD_COLOURS = {ChatFormatting.WHITE, ChatFormatting.GREEN, ChatFormatting.YELLOW};

    private final Component banner;
    private final Component body;
    private final Component acceptLabel;
    private final Consumer<Boolean> callback;
    private final Screen previous = Minecraft.getInstance().screen;
    private int remainingTicks = ACCEPT_DELAY_TICKS;
    private boolean decided;
    private Button acceptButton;
    private int blockTop;
    private int bodyHeight;

    private ConsentScreen(String titleKey, Component body, String acceptKey, Consumer<Boolean> callback) {
        super(Component.translatable(titleKey));
        this.banner = BannerBox.title(title.getString());
        this.body = body;
        this.acceptLabel = Component.translatable(acceptKey);
        this.callback = callback;
    }

    public static ConsentScreen consent(Consumer<Boolean> callback) {
        return new ConsentScreen("wynnvoicechat.consent.title",
                paragraphs(CONSENT_COLOURS, "notSvc", "position", "recording", "moderation", "audience"),
                "wynnvoicechat.consent.accept", callback);
    }

    public static ConsentScreen everyoneWarning(Consumer<Boolean> callback) {
        return new ConsentScreen("wynnvoicechat.consent.everyone.title",
                paragraphs(EVERYONE_COLOURS, "everyone.rules", "everyone.report", "everyone.ban"),
                "wynnvoicechat.consent.everyone.accept", callback);
    }

    public static ConsentScreen guildWarning(Consumer<Boolean> callback) {
        return new ConsentScreen("wynnvoicechat.consent.guild.title",
                paragraphs(GUILD_COLOURS, "guild.channel", "guild.report", "guild.leave"),
                "wynnvoicechat.consent.guild.accept", callback);
    }

    private static Component paragraphs(ChatFormatting[] colours, String... keys) {
        MutableComponent text = Component.empty().withStyle(WYNNCRAFT_FONT);
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) text.append("\n\n");
            text.append(Component.translatable("wynnvoicechat.consent." + keys[i], VoiceMod.COMMAND).withStyle(colours[i]));
        }
        return text;
    }

    @Override
    protected void init() {
        super.init();
        bodyHeight = font.wordWrapHeight(body, BODY_WIDTH);
        int blockHeight = TITLE_HEIGHT + bodyHeight + BUTTON_GAP + BUTTON_HEIGHT;
        blockTop = Math.max(10, (height - blockHeight) / 2);
        int buttonY = blockTop + TITLE_HEIGHT + bodyHeight + BUTTON_GAP;
        int centerX = width / 2;
        acceptButton = addRenderableWidget(Button.builder(acceptLabel, b -> decide(true)).pos(centerX - 155, buttonY).size(150, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("wynnvoicechat.consent.decline"), b -> decide(false)).pos(centerX + 5, buttonY).size(150, BUTTON_HEIGHT).build());
        updateAcceptButton();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - BODY_WIDTH / 2;
        int panelX = left - PANEL_PADDING;
        int panelY = blockTop - PANEL_PADDING;
        int panelWidth = BODY_WIDTH + PANEL_PADDING * 2;
        int panelHeight = TITLE_HEIGHT + bodyHeight + BUTTON_GAP + BUTTON_HEIGHT + PANEL_PADDING * 2;
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
        graphics.renderOutline(panelX, panelY, panelWidth, panelHeight, ACCENT);

        graphics.pose().pushMatrix();
        graphics.pose().scale(2F, 2F);
        graphics.drawString(font, banner, width / 4 - font.width(banner) / 2, blockTop / 2, 0xFFFFFFFF, false);
        graphics.pose().popMatrix();

        graphics.drawWordWrap(font, body, left, blockTop + TITLE_HEIGHT, BODY_WIDTH, 0xFFFFFFFF, true);
    }

    @Override
    public void tick() {
        if (remainingTicks == 0) return;
        remainingTicks--;
        updateAcceptButton();
    }

    // Give people no way to click through without at least seeing the notice
    private void updateAcceptButton() {
        acceptButton.active = remainingTicks == 0;
        acceptButton.setMessage(remainingTicks == 0
                ? acceptLabel
                : Component.translatable("wynnvoicechat.consent.acceptWait", (remainingTicks + 19) / 20));
    }

    @Override
    public void onClose() {
        decide(false);
    }

    private void decide(boolean accepted) {
        if (decided) return;
        decided = true;
        minecraft.setScreen(previous);
        callback.accept(accepted);
    }

    /**
     * Wynncraft's {@code banner/box} font: a coloured box glyph per character (U+E030…) with the letter (U+E000…) drawn
     * back over it through a negative advance from the pack's {@code space} font (zero at U+D0000).
     */
    static final class BannerBox {
        private static final Style FONT = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.withDefaultNamespace("banner/box")));
        private static final String GLYPHS = "abcdefghijklmnopqrstuvwxyz?[]/%&0123456789!()<=>";
        private static final int FOREGROUND = 0xE000;
        private static final int BACKGROUND = 0xE030;
        private static final int LEFT_EDGE = 0xE060;
        private static final int SPACE = 0xE061;
        private static final int RIGHT_EDGE = 0xE062;
        private static final int ZERO_ADVANCE = 0xD0000;
        private static final String OVERLAP = new String(Character.toChars(ZERO_ADVANCE - 1));
        private static final int OVERSHOOT = 2;

        private BannerBox() {}

        static Component title(String text) {
            return build(text.toLowerCase(Locale.ROOT), 0xACFAFA, 0x000000);
        }

        static Component build(String text, int background, int foreground) {
            StringBuilder boxes = new StringBuilder().appendCodePoint(LEFT_EDGE).append(OVERLAP);
            StringBuilder letters = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int index = GLYPHS.indexOf(c);
                boxes.appendCodePoint(c == ' ' || index < 0 ? SPACE : BACKGROUND + index).append(OVERLAP);
                if (index < 0) letters.append(c);
                else letters.appendCodePoint(FOREGROUND + index);
            }
            boxes.appendCodePoint(RIGHT_EDGE);
            MutableComponent component = Component.empty().withStyle(FONT);
            component.append(Component.literal(boxes.toString()).withColor(background));
            int back = OVERSHOOT - Minecraft.getInstance().font.width(component);
            component.append(new String(Character.toChars(ZERO_ADVANCE + back)));
            component.append(Component.literal(letters.toString()).withColor(foreground));
            return component;
        }
    }
}
