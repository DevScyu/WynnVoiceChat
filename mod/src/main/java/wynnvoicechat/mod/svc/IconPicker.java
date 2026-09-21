package wynnvoicechat.mod.svc;

import net.minecraft.resources.Identifier;
import wynnvoicechat.protocol.Peer;

/** The nameplate glyph for a voice peer: one white speaker sprite, tinted by relation, crossed when they cannot hear us, waveless and dimmed while they are quiet. */
public final class IconPicker {
    public record Icon(Identifier texture, int argb) {}

    public static final Identifier SPEAKER = icon("speaker");
    public static final Identifier SPEAKER_OFF = icon("speaker_off");
    public static final Identifier SPEAKER_QUIET = icon("speaker_quiet");

    private IconPicker() {}

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath("wynnvoicechat", "textures/icons/" + name + ".png");
    }

    public static Icon iconFor(Peer peer) {
        if (peer.disabled() || !peer.reachable()) return new Icon(SPEAKER_OFF, 0xFFFF5555);
        return new Icon(SPEAKER, tint(peer));
    }

    /** The glyph for a peer we could hear but who is not talking; null when there is nothing to mark. */
    public static Icon idleFor(Peer peer) {
        if (peer.disabled() || !peer.reachable()) return null;
        return new Icon(SPEAKER_QUIET, dim(tint(peer)));
    }

    private static int tint(Peer peer) {
        return switch (peer.relation()) {
            case PARTY -> 0xFF55FF55;
            case FRIEND -> 0xFF55AAFF;
            case GUILD -> 0xFFFFC828;
            case NONE -> 0xFFFFFFFF;
        };
    }

    private static int dim(int argb) {
        int r = (argb >> 16 & 0xFF) * 6 / 10, g = (argb >> 8 & 0xFF) * 6 / 10, b = (argb & 0xFF) * 6 / 10;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
