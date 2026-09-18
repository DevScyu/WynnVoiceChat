package wynnvoice.mod.svc;

import net.minecraft.resources.Identifier;
import wynnvoice.protocol.Peer;

public final class IconPicker {
    public static final Identifier PARTY = icon("party");
    public static final Identifier FRIEND = icon("friend");
    public static final Identifier GUILD = icon("guild");
    public static final Identifier STRANGER = icon("stranger");
    public static final Identifier UNREACHABLE = icon("unreachable");

    private IconPicker() {}

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath("wynnvoice", "textures/icons/" + name + ".png");
    }

    public static Identifier iconFor(Peer peer) {
        if (peer.disabled() || !peer.reachable()) return UNREACHABLE;
        return switch (peer.relation()) {
            case PARTY -> PARTY;
            case FRIEND -> FRIEND;
            case GUILD -> GUILD;
            case NONE -> STRANGER;
        };
    }
}
