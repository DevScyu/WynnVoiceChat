package wynnvoicechat.mod.svc;

import net.minecraft.resources.Identifier;
import wynnvoicechat.protocol.Peer;

/** The nameplate glyph for a voice peer: one white speaker sprite, tinted by relation, crossed when they cannot hear us. */
public final class IconPicker {
    public record Icon(Identifier texture, int argb) {}

    public static final Identifier SPEAKER = icon("speaker");
    public static final Identifier SPEAKER_OFF = icon("speaker_off");

    private IconPicker() {}

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath("wynnvoicechat", "textures/icons/" + name + ".png");
    }

    public static Icon iconFor(Peer peer) {
        if (peer.disabled() || !peer.reachable()) return new Icon(SPEAKER_OFF, 0xFFFF5555);
        return new Icon(SPEAKER, switch (peer.relation()) {
            case PARTY -> 0xFF55FF55;
            case FRIEND -> 0xFF55AAFF;
            case GUILD -> 0xFFFFC828;
            case NONE -> 0xFFFFFFFF;
        });
    }
}
