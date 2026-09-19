package wynnvoicechat.mod.hud;

import java.util.UUID;
import wynnvoicechat.protocol.CallAction;
import wynnvoicechat.protocol.CallStateKind;
import wynnvoicechat.protocol.Packet;

/** What the call overlay shows: the last call state, who with, and since when. Pure; the renderer and keybinds read it. */
public final class CallHud {
    /** How long a finished call's outcome stays on screen. */
    public static final long LINGER_MS = 2_000;

    private CallStateKind state;
    private String peer;
    private UUID peerUuid;
    private long since;

    public void onState(Packet.CallState next, long now) {
        state = next.state();
        peer = next.peerName();
        peerUuid = next.peerUuid();
        since = now;
    }

    public void clear() {
        state = null;
        peer = null;
        peerUuid = null;
    }

    /** Null when there is nothing to draw. */
    public CallStateKind state(long now) {
        if (state != null && isOver(state) && now - since >= LINGER_MS) clear();
        return state;
    }

    public String peer() {
        return peer;
    }

    public UUID peerUuid() {
        return peerUuid;
    }

    /** Milliseconds since the call became {@code ACTIVE}. */
    public long durationMs(long now) {
        return now - since;
    }

    private static boolean isOver(CallStateKind state) {
        return switch (state) {
            case RINGING, INCOMING, ACTIVE -> false;
            case DECLINED, NO_ANSWER, ENDED, BUSY, DND, EXPIRED -> true;
        };
    }

    /** The packet a call keybind sends in this state, or null when the key means nothing right now. */
    public static Packet.Call actionFor(CallAction key, CallStateKind state) {
        boolean allowed = switch (key) {
            case ACCEPT, DECLINE -> state == CallStateKind.INCOMING;
            case HANGUP -> state == CallStateKind.RINGING || state == CallStateKind.ACTIVE;
            case INVITE -> false;
        };
        return allowed ? new Packet.Call("", key) : null;
    }
}
