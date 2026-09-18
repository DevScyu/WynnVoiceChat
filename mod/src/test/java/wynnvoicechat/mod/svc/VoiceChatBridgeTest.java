package wynnvoicechat.mod.svc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class VoiceChatBridgeTest {
    @Test
    void readsServerboundVoicechatPayload() {
        // Vanilla's DiscardedPayload carries no bytes; the real client re-encodes SVC's registered codec.
        Identifier channel = Identifier.fromNamespaceAndPath("voicechat", "request_secret");
        VoiceChatBridge.Intercepted intercepted = VoiceChatBridge.readServerbound(new ServerboundCustomPayloadPacket(new DiscardedPayload(channel)));
        assertNotNull(intercepted);
        assertEquals(channel, intercepted.channel());
        assertEquals(0, intercepted.data().readableBytes());
    }

    @Test
    void ignoresNonVoicechatPayload() {
        DiscardedPayload payload = new DiscardedPayload(Identifier.parse("minecraft:brand"));
        assertNull(VoiceChatBridge.readServerbound(new ServerboundCustomPayloadPacket(payload)));
    }
}
