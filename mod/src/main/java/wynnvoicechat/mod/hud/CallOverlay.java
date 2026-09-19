package wynnvoicechat.mod.hud;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;
import org.lwjgl.glfw.GLFW;
import wynnvoicechat.mod.VoiceMod;
import wynnvoicechat.protocol.CallAction;
import wynnvoicechat.protocol.CallStateKind;
import wynnvoicechat.protocol.Packet;

/**
 * Draws {@link CallHud} top-right under the toast slot and owns the accept, decline and hang-up keys. Hidden with the
 * vanilla HUD (F1) like every element attached to a vanilla layer. Render thread only.
 */
public final class CallOverlay implements HudElement {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(VoiceMod.MOD_ID, "call");
    private static final Identifier PANEL = Identifier.withDefaultNamespace("toast/advancement");
    private static final Identifier HANGUP = Identifier.fromNamespaceAndPath(VoiceMod.MOD_ID, "call/hangup");
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(VoiceMod.MOD_ID, "voice"));
    private static final int TOP = 36;
    private static final int MARGIN = 4;
    private static final int PAD = 4;
    private static final int ICON = 16;
    private static final int PANEL_WIDTH = 160;
    private static final int PANEL_HEIGHT = 32;
    private static final int PANEL_INSET = 8;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TITLE = 0xFFFFFF00;
    private static final int PILL = 0x80000000;
    private static final int BADGE = 0xFF6F6F6F;
    private static final int OUTLINE = 0xFF000000;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final CallHud hud;
    private final BooleanSupplier enabled;
    // Y is the one letter neither vanilla, Wynntils nor Simple Voice Chat binds; N would also disable SVC and H hide its icons
    private final KeyMapping accept = key("accept", GLFW.GLFW_KEY_Y);
    private final KeyMapping decline = key("decline", GLFW.GLFW_KEY_UNKNOWN);
    private final KeyMapping hangUp = key("hangup", GLFW.GLFW_KEY_UNKNOWN);
    // ponytail: a failed Mojang lookup sticks for the session (Steve/Alex shows instead); evict on failure if that ever grates
    private final Map<UUID, CompletableFuture<Optional<PlayerSkin>>> skins = new HashMap<>();

    public CallOverlay(CallHud hud, BooleanSupplier enabled) {
        this.hud = hud;
        this.enabled = enabled;
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ID, this);
    }

    private static KeyMapping key(String name, int glfwKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping("key.wynnvoicechat." + name, glfwKey, CATEGORY));
    }

    /** Called every client tick: the packet a pressed call key asks for in the current state, or null. */
    public Packet.Call poll(long now) {
        CallStateKind state = hud.state(now);
        Packet.Call wanted = null;
        if (accept.consumeClick()) wanted = CallHud.actionFor(CallAction.ACCEPT, state);
        if (decline.consumeClick()) wanted = CallHud.actionFor(CallAction.DECLINE, state);
        if (hangUp.consumeClick()) wanted = CallHud.actionFor(CallAction.HANGUP, state);
        return wanted;
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        if (!enabled.getAsBoolean()) return;
        long now = Util.getMillis();
        CallStateKind state = hud.state(now);
        if (state == null) return;
        Font font = minecraft.font;
        String peer = hud.peer();
        switch (state) {
            case INCOMING -> panel(graphics, font, peer);
            case RINGING -> pill(graphics, font, face(), Component.translatable("wynnvoicechat.call.ringing", peer), bound(hangUp));
            case ACTIVE -> pill(graphics, font, face(), Component.literal(peer + "  " + clock(hud.durationMs(now))), bound(hangUp));
            default -> pill(graphics, font, Icon.sprite(HANGUP), Component.translatable(lineKey(state), peer), null);
        }
    }

    /** The chat line's key doubles as the pill text for every finished state. */
    static String lineKey(CallStateKind state) {
        return "wynnvoicechat.call." + state.name().toLowerCase(Locale.ROOT);
    }

    static String clock(long millis) {
        long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    /** Null when the key is unbound, so no badge is drawn for it; the chat buttons and commands still work. */
    private static KeyMapping bound(KeyMapping key) {
        return key.isUnbound() ? null : key;
    }

    private void panel(GuiGraphics graphics, Font font, String peer) {
        Component title = Component.translatable("wynnvoicechat.call.incoming", peer);
        Component acceptLabel = Component.translatable("wynnvoicechat.hud.accept");
        Component declineLabel = Component.translatable("wynnvoicechat.hud.decline");
        int textX = PANEL_INSET + ICON + PAD;
        int hints = hintWidth(font, bound(accept), acceptLabel) + hintWidth(font, bound(decline), declineLabel);
        int width = Math.max(PANEL_WIDTH, textX + Math.max(font.width(title), hints) + PAD);
        int x = graphics.guiWidth() - MARGIN - width;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, TOP, width, PANEL_HEIGHT);
        face().draw(graphics, x + PANEL_INSET, TOP + (PANEL_HEIGHT - ICON) / 2);
        graphics.drawString(font, title, x + textX, TOP + 7, TITLE, false);
        int hintX = hint(graphics, font, bound(accept), acceptLabel, x + textX);
        hint(graphics, font, bound(decline), declineLabel, hintX);
    }

    private static int hintWidth(Font font, KeyMapping key, Component label) {
        return key == null ? 0 : badgeWidth(font, key) + 2 + font.width(label) + PAD * 2;
    }

    /** "[key] label" at x for a bound key, nothing for an unbound one; returns the x just past it. */
    private static int hint(GuiGraphics graphics, Font font, KeyMapping key, Component label, int x) {
        if (key == null) return x;
        int labelX = badge(graphics, font, key, x, TOP + 18) + 2;
        graphics.drawString(font, label, labelX, TOP + 19, TEXT, false);
        return labelX + font.width(label) + PAD * 2;
    }

    private void pill(GuiGraphics graphics, Font font, Icon icon, Component text, KeyMapping key) {
        int width = PAD + ICON + PAD + font.width(text) + PAD + (key == null ? 0 : badgeWidth(font, key) + PAD);
        int height = ICON + PAD * 2;
        int x = graphics.guiWidth() - MARGIN - width;
        graphics.fill(x, TOP, x + width, TOP + height, PILL);
        icon.draw(graphics, x + PAD, TOP + PAD);
        graphics.drawString(font, text, x + PAD + ICON + PAD, TOP + PAD + (ICON - font.lineHeight) / 2 + 1, TEXT, true);
        if (key != null) badge(graphics, font, key, x + width - PAD - badgeWidth(font, key), TOP + PAD + (ICON - 11) / 2);
    }

    /** A vanilla-button-coloured box round the key's name; returns the x just past it. */
    private static int badge(GuiGraphics graphics, Font font, KeyMapping key, int x, int y) {
        Component label = key.getTranslatedKeyMessage();
        int width = badgeWidth(font, key);
        graphics.fill(x, y, x + width, y + 11, BADGE);
        graphics.renderOutline(x, y, width, 11, OUTLINE);
        graphics.drawString(font, label, x + 3, y + 2, TEXT, false);
        return x + width;
    }

    private static int badgeWidth(Font font, KeyMapping key) {
        return font.width(key.getTranslatedKeyMessage()) + 6;
    }

    private interface Icon {
        void draw(GuiGraphics graphics, int x, int y);

        static Icon sprite(Identifier sprite) {
            return (graphics, x, y) -> graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, ICON, ICON);
        }
    }

    /** The peer's face: from the tab list when they share our world, else Mojang's profile service, Steve/Alex meanwhile. */
    private Icon face() {
        UUID uuid = hud.peerUuid();
        ClientPacketListener connection = minecraft.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(uuid);
        PlayerSkin skin = info != null ? info.getSkin()
                : skins.computeIfAbsent(uuid, this::lookup).getNow(Optional.empty()).orElseGet(() -> DefaultPlayerSkin.get(uuid));
        return (graphics, x, y) -> PlayerFaceRenderer.draw(graphics, skin, x, y, ICON);
    }

    private CompletableFuture<Optional<PlayerSkin>> lookup(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> minecraft.services().sessionService().fetchProfile(uuid, false), Util.backgroundExecutor())
                .thenCompose(result -> result == null
                        ? CompletableFuture.completedFuture(Optional.<PlayerSkin>empty())
                        : minecraft.getSkinManager().get(result.profile()))
                .exceptionally(error -> Optional.empty());
    }
}
