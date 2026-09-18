package wynnvoice.mod.screen;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import wynnvoice.mod.VoiceMod;

/** Consent notice and the everyone-audience rules. Nothing risky starts without an explicit accept: closing declines. */
public final class ConsentScreen extends Screen {
    private static final int ACCEPT_DELAY_TICKS = 60;
    private static final int BODY_WIDTH = 400;
    private static final ChatFormatting[] CONSENT_COLOURS = {ChatFormatting.GOLD, ChatFormatting.WHITE, ChatFormatting.RED, ChatFormatting.WHITE, ChatFormatting.GREEN};
    private static final ChatFormatting[] EVERYONE_COLOURS = {ChatFormatting.WHITE, ChatFormatting.GREEN, ChatFormatting.RED};

    private final Component body;
    private final Component acceptLabel;
    private final Consumer<Boolean> callback;
    private final Screen previous = Minecraft.getInstance().screen;
    private final LinearLayout layout = LinearLayout.vertical().spacing(8);
    private int remainingTicks = ACCEPT_DELAY_TICKS;
    private boolean decided;
    private Button acceptButton;

    private ConsentScreen(String titleKey, Component body, String acceptKey, Consumer<Boolean> callback) {
        super(Component.translatable(titleKey));
        this.body = body;
        this.acceptLabel = Component.translatable(acceptKey);
        this.callback = callback;
    }

    public static ConsentScreen consent(Consumer<Boolean> callback) {
        return new ConsentScreen("wynnvoice.consent.title",
                paragraphs(CONSENT_COLOURS, "notSvc", "position", "recording", "moderation", "audience"),
                "wynnvoice.consent.accept", callback);
    }

    public static ConsentScreen everyoneWarning(Consumer<Boolean> callback) {
        return new ConsentScreen("wynnvoice.consent.everyone.title",
                paragraphs(EVERYONE_COLOURS, "everyone.rules", "everyone.report", "everyone.ban"),
                "wynnvoice.consent.everyone.accept", callback);
    }

    private static Component paragraphs(ChatFormatting[] colours, String... keys) {
        MutableComponent text = Component.empty();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) text.append("\n\n");
            text.append(Component.translatable("wynnvoice.consent." + keys[i], VoiceMod.COMMAND).withStyle(colours[i]));
        }
        return text;
    }

    @Override
    protected void init() {
        super.init();
        layout.defaultCellSetting().alignHorizontallyCenter();
        layout.addChild(new StringWidget(title, font));
        layout.addChild(new MultiLineTextWidget(body, font).setMaxWidth(Math.min(width - 40, BODY_WIDTH)));
        LinearLayout buttons = layout.addChild(LinearLayout.horizontal().spacing(8));
        buttons.defaultCellSetting().paddingTop(8);
        acceptButton = buttons.addChild(Button.builder(acceptLabel, b -> decide(true)).build());
        buttons.addChild(Button.builder(Component.translatable("wynnvoice.consent.decline"), b -> decide(false)).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
        updateAcceptButton();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        FrameLayout.centerInRectangle(layout, getRectangle());
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
                : Component.translatable("wynnvoice.consent.acceptWait", (remainingTicks + 19) / 20));
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
}
