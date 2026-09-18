package wynnvoicechat.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import wynnvoicechat.protocol.AuthStatus;
import wynnvoicechat.protocol.EndReason;
import wynnvoicechat.protocol.Packet;
import wynnvoicechat.protocol.Protocol;

class VoiceClientHandlerTest {
    private final VoiceClient.Identity identity = new VoiceClient.Identity("Player", UUID.randomUUID(), "1.0.0");
    private final List<String> joined = new ArrayList<>();
    private final List<AuthStatus> results = new ArrayList<>();
    private final List<Packet> packets = new ArrayList<>();
    private int closed;

    private final VoiceClient.Listener listener = new VoiceClient.Listener() {
        @Override
        public void onAuthResult(AuthStatus status) {
            results.add(status);
        }

        @Override
        public void onPacket(Packet packet) {
            packets.add(packet);
        }

        @Override
        public void onClosed() {
            closed++;
        }
    };

    private EmbeddedChannel channel(VoiceClient.SessionJoiner joiner) {
        return new EmbeddedChannel(new VoiceClient.Handler(identity, joiner, Runnable::run, listener));
    }

    private static Packet.AuthChallenge challenge() {
        byte[] serverId = new byte[Protocol.SERVER_ID_BYTES];
        for (int i = 0; i < serverId.length; i++) serverId[i] = (byte) i;
        return new Packet.AuthChallenge(serverId);
    }

    @Test
    void sendsHelloOnConnect() {
        EmbeddedChannel ch = channel(joined::add);
        assertEquals(new Packet.Hello(Protocol.VERSION, VoiceClient.SVC_COMPAT_VERSION, "1.0.0"), ch.readOutbound());
    }

    @Test
    void answersChallengeAfterJoiningServer() {
        EmbeddedChannel ch = channel(joined::add);
        ch.readOutbound();
        ch.writeInbound(challenge());
        ch.runPendingTasks();
        assertEquals(List.of(HexFormat.of().formatHex(challenge().serverId())), joined);
        assertEquals(new Packet.Auth("Player", identity.uuid()), ch.readOutbound());

        ch.writeInbound(new Packet.AuthResult(AuthStatus.OK));
        assertEquals(List.of(AuthStatus.OK), results);
        assertTrue(ch.isOpen());
    }

    @Test
    void mojangOutageReportsSessionUnavailableAndCloses() {
        EmbeddedChannel ch = channel(serverId -> { throw new AuthenticationUnavailableException("down"); });
        ch.readOutbound();
        ch.writeInbound(challenge());
        ch.runPendingTasks();
        assertNull(ch.readOutbound());
        assertEquals(List.of(AuthStatus.SESSION_UNAVAILABLE), results);
        assertFalse(ch.isOpen());
        assertEquals(1, closed);
    }

    @Test
    void rejectedSessionReportsBadSession() {
        EmbeddedChannel ch = channel(serverId -> { throw new InvalidCredentialsException("stale token"); });
        ch.readOutbound();
        ch.writeInbound(challenge());
        ch.runPendingTasks();
        assertEquals(List.of(AuthStatus.BAD_SESSION), results);
        assertFalse(ch.isOpen());
    }

    @Test
    void refusalIsReported() {
        EmbeddedChannel ch = channel(joined::add);
        ch.writeInbound(new Packet.AuthResult(AuthStatus.VERSION_MISMATCH));
        assertEquals(List.of(AuthStatus.VERSION_MISMATCH), results);
    }

    @Test
    void packetsOnlyReachListenerOnceAuthenticated() {
        EmbeddedChannel ch = channel(joined::add);
        Packet.Ended ended = new Packet.Ended(EndReason.TIMED_OUT, "bye");
        ch.writeInbound(ended);
        assertTrue(packets.isEmpty());
        ch.writeInbound(new Packet.AuthResult(AuthStatus.OK));
        ch.writeInbound(ended);
        assertEquals(List.of(ended), packets);
    }

    @Test
    void closeNotifiesListener() {
        EmbeddedChannel ch = channel(joined::add);
        ch.close();
        assertEquals(1, closed);
    }
}
