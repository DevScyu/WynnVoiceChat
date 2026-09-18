package wynnvoicechat.mod.svc;

import io.netty.buffer.Unpooled;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates between the mod and Simple Voice Chat's plugin-message channels using Minecraft's own
 * payload registry. SVC registers its codecs with the loader's payload registry, so
 * {@link ClientboundCustomPayloadPacket#GAMEPLAY_STREAM_CODEC} decodes our raw bytes into SVC's
 * real payload objects (which its receivers then handle), and
 * {@link ServerboundCustomPayloadPacket#STREAM_CODEC} re-encodes SVC's outbound payloads back to
 * bytes. Nothing here references an SVC class.
 */
public final class VoiceChatBridge {
    private static final Logger LOG = LoggerFactory.getLogger("wynnvoicechat");

    private VoiceChatBridge() {}

    public record Intercepted(Identifier channel, FriendlyByteBuf data) {}

    public static ClientboundCustomPayloadPacket toClientboundPacket(ClientPacketListener connection, Identifier channelId, Consumer<FriendlyByteBuf> writer) {
        RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), connection.registryAccess());
        wire.writeIdentifier(channelId);
        writer.accept(wire);
        return ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(wire);
    }

    /** Inject a clientbound voicechat message into the client as if the server had sent it. */
    public static void inject(Identifier channelId, Consumer<FriendlyByteBuf> writer) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            ClientPacketListener connection = minecraft.getConnection();
            if (connection == null) {
                LOG.warn("Tried to inject voice payload {} with no connection", channelId);
                return;
            }
            try {
                connection.handleCustomPayload(toClientboundPacket(connection, channelId, writer));
            } catch (Exception e) {
                LOG.error("Failed to inject voice payload {}", channelId, e);
            }
        });
    }

    /** Extract a serverbound voicechat message's channel and bytes, or null if it is not voicechat. */
    @Nullable
    public static Intercepted readServerbound(ServerboundCustomPayloadPacket packet) {
        Identifier channel = packet.payload().type().id();
        if (!VoiceChatPayloads.isVoicechat(channel)) return null;

        // The loader's payload registry resolves play-phase serverbound codecs only for a registry buffer;
        // a plain buffer is treated as configuration-phase where SVC has nothing registered.
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft != null ? minecraft.getConnection() : null;
        FriendlyByteBuf wire = connection != null
                ? new RegistryFriendlyByteBuf(Unpooled.buffer(), connection.registryAccess())
                : new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerboundCustomPayloadPacket.STREAM_CODEC.encode(wire, packet);
            wire.readIdentifier();
        } catch (Exception e) {
            // No registered codec for this channel (SVC not loaded): keep the channel, drop the bytes
            LOG.warn("Could not re-encode voice payload {}", channel, e);
            wire.clear();
        }
        return new Intercepted(channel, wire);
    }
}
