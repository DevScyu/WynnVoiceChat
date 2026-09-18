package wynnvoice.mod.svc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class VoiceChatPayloadsTest {
    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void channelIdsAreVoicechatNamespaced() {
        assertEquals(Identifier.fromNamespaceAndPath("voicechat", "secret"), VoiceChatPayloads.SECRET);
        assertEquals(Identifier.fromNamespaceAndPath("voicechat", "request_secret"), VoiceChatPayloads.REQUEST_SECRET);
        assertTrue(VoiceChatPayloads.isVoicechat(VoiceChatPayloads.UPDATE_STATE));
        assertFalse(VoiceChatPayloads.isVoicechat(Identifier.parse("minecraft:brand")));
    }

    @Test
    void secretMatchesSvcCompat20Layout() {
        byte[] secret = new byte[16];
        for (int i = 0; i < 16; i++) secret[i] = (byte) i;
        UUID player = UUID.randomUUID();
        FriendlyByteBuf buf = buffer();

        VoiceChatPayloads.writeSecret(buf, secret, 24454, player, 32.0, 1000, "voice.example.org");

        byte[] readSecret = new byte[16];
        buf.readBytes(readSecret);
        assertArrayEquals(secret, readSecret);
        assertEquals(24454, buf.readInt());
        assertEquals(player, buf.readUUID());
        assertEquals(0, buf.readByte(), "codec VOIP");
        assertEquals(1275, buf.readInt(), "mtu");
        assertEquals(32.0, buf.readDouble());
        assertEquals(1000, buf.readInt());
        assertTrue(buf.readBoolean(), "groupsEnabled");
        assertEquals("voice.example.org", buf.readUtf(32767));
        assertFalse(buf.readBoolean(), "allowRecording");
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void statesLayout() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        FriendlyByteBuf buf = buffer();

        VoiceChatPayloads.writeStates(buf, List.of(
                new VoiceChatPayloads.State(a, "A", true, false, null),
                new VoiceChatPayloads.State(b, "B", false, true, group)));

        assertEquals(2, buf.readInt());
        assertTrue(buf.readBoolean(), "A disabled");
        assertFalse(buf.readBoolean(), "A disconnected");
        assertEquals(a, buf.readUUID());
        assertEquals("A", buf.readUtf(32767));
        assertFalse(buf.readBoolean(), "A has no group");
        assertFalse(buf.readBoolean(), "B disabled");
        assertTrue(buf.readBoolean(), "B disconnected");
        assertEquals(b, buf.readUUID());
        assertEquals("B", buf.readUtf(32767));
        assertTrue(buf.readBoolean(), "B has group");
        assertEquals(group, buf.readUUID());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void readsServerboundPayloads() {
        FriendlyByteBuf request = buffer();
        request.writeInt(20);
        assertEquals(20, VoiceChatPayloads.readRequestSecretVersion(request));

        FriendlyByteBuf state = buffer();
        state.writeBoolean(true);
        assertTrue(VoiceChatPayloads.readUpdateStateDisabled(state));
    }
}
