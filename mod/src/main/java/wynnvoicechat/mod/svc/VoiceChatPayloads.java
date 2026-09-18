package wynnvoicechat.mod.svc;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Simple Voice Chat compat-20 plugin-message payloads, encoded with Minecraft's own FriendlyByteBuf
 * (the exact wire format SVC uses). No SVC class is referenced; the mod only ever handles the bytes.
 */
public final class VoiceChatPayloads {
    private static final String NAMESPACE = "voicechat";

    // Server -> client (the mod synthesizes these)
    public static final Identifier SECRET = id("secret");
    public static final Identifier STATES = id("states");
    public static final Identifier ADD_GROUP = id("add_group");
    public static final Identifier JOINED_GROUP = id("joined_group");

    // Client -> server (the mod intercepts these)
    public static final Identifier REQUEST_SECRET = id("request_secret");
    public static final Identifier UPDATE_STATE = id("update_state");
    public static final Identifier CREATE_GROUP = id("create_group");
    public static final Identifier SET_GROUP = id("set_group");
    public static final Identifier LEAVE_GROUP = id("leave_group");

    private static final byte CODEC_VOIP = 0;
    private static final int MTU = 1275;
    private static final short GROUP_TYPE_NORMAL = 0;

    private VoiceChatPayloads() {}

    public record State(UUID uuid, String name, boolean disabled, boolean disconnected, @Nullable UUID group) {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    public static boolean isVoicechat(Identifier channel) {
        return channel.getNamespace().equals(NAMESPACE);
    }

    public static void writeSecret(FriendlyByteBuf buf, byte[] secret, int port, UUID playerUuid, double distance, int keepAlive, String voiceHost) {
        buf.writeBytes(secret);
        buf.writeInt(port);
        buf.writeUUID(playerUuid);
        buf.writeByte(CODEC_VOIP);
        buf.writeInt(MTU);
        buf.writeDouble(distance);
        buf.writeInt(keepAlive);
        buf.writeBoolean(true); // groupsEnabled
        buf.writeUtf(voiceHost);
        buf.writeBoolean(false); // allowRecording: the relay's report evidence is the only recording
    }

    public static void writeStates(FriendlyByteBuf buf, List<State> states) {
        buf.writeInt(states.size());
        for (State state : states) {
            buf.writeBoolean(state.disabled());
            buf.writeBoolean(state.disconnected());
            buf.writeUUID(state.uuid());
            buf.writeUtf(state.name());
            buf.writeBoolean(state.group() != null);
            if (state.group() != null) buf.writeUUID(state.group());
        }
    }

    public static void writeAddGroup(FriendlyByteBuf buf, UUID id, String name) {
        buf.writeUUID(id);
        buf.writeUtf(name, 512);
        buf.writeBoolean(false); // hasPassword
        buf.writeBoolean(false); // persistent
        buf.writeBoolean(false); // hidden
        buf.writeShort(GROUP_TYPE_NORMAL);
    }

    public static void writeJoinedGroup(FriendlyByteBuf buf, @Nullable UUID group) {
        buf.writeBoolean(group != null);
        if (group != null) buf.writeUUID(group);
        buf.writeBoolean(false); // wrongPassword
    }

    public static int readRequestSecretVersion(FriendlyByteBuf buf) {
        return buf.readInt();
    }

    public static boolean readUpdateStateDisabled(FriendlyByteBuf buf) {
        return buf.readBoolean();
    }
}
