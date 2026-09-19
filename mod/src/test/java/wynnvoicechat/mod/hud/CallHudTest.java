package wynnvoicechat.mod.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import wynnvoicechat.protocol.CallAction;
import wynnvoicechat.protocol.CallStateKind;
import wynnvoicechat.protocol.Packet;

class CallHudTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private final CallHud hud = new CallHud();

    private void state(CallStateKind kind, long now) {
        hud.onState(new Packet.CallState("Alice", ALICE, kind), now);
    }

    @Test
    void ringingAndActiveStayUpUntilTheNextState() {
        state(CallStateKind.RINGING, 1_000);
        assertEquals(CallStateKind.RINGING, hud.state(60_000));
        state(CallStateKind.ACTIVE, 2_000);
        assertEquals(CallStateKind.ACTIVE, hud.state(600_000));
        assertEquals("Alice", hud.peer());
        assertEquals(ALICE, hud.peerUuid());
    }

    @Test
    void activeDurationCountsFromTheAcceptNotTheRing() {
        state(CallStateKind.RINGING, 1_000);
        state(CallStateKind.ACTIVE, 5_000);
        assertEquals(197_000, hud.durationMs(202_000));
    }

    @Test
    void endStatesLingerTwoSecondsThenClear() {
        for (CallStateKind end : new CallStateKind[] {CallStateKind.ENDED, CallStateKind.DECLINED, CallStateKind.NO_ANSWER,
                CallStateKind.BUSY, CallStateKind.DND, CallStateKind.EXPIRED}) {
            state(end, 10_000);
            assertEquals(end, hud.state(11_999), end.name());
            assertNull(hud.state(12_000), end.name());
        }
    }

    @Test
    void clearingDropsWhateverWasShowing() {
        state(CallStateKind.INCOMING, 0);
        hud.clear();
        assertNull(hud.state(0));
        assertNull(hud.peer());
    }

    @Test
    void acceptAndDeclineOnlyAnswerAnIncomingCall() {
        assertEquals(new Packet.Call("", CallAction.ACCEPT), CallHud.actionFor(CallAction.ACCEPT, CallStateKind.INCOMING));
        assertEquals(new Packet.Call("", CallAction.DECLINE), CallHud.actionFor(CallAction.DECLINE, CallStateKind.INCOMING));
        for (CallStateKind other : CallStateKind.values()) {
            if (other == CallStateKind.INCOMING) continue;
            assertNull(CallHud.actionFor(CallAction.ACCEPT, other), other.name());
            assertNull(CallHud.actionFor(CallAction.DECLINE, other), other.name());
        }
        assertNull(CallHud.actionFor(CallAction.ACCEPT, null));
    }

    @Test
    void hangUpOnlyWhileRingingOrInACall() {
        assertEquals(new Packet.Call("", CallAction.HANGUP), CallHud.actionFor(CallAction.HANGUP, CallStateKind.RINGING));
        assertEquals(new Packet.Call("", CallAction.HANGUP), CallHud.actionFor(CallAction.HANGUP, CallStateKind.ACTIVE));
        assertNull(CallHud.actionFor(CallAction.HANGUP, CallStateKind.INCOMING));
        assertNull(CallHud.actionFor(CallAction.HANGUP, CallStateKind.ENDED));
        assertNull(CallHud.actionFor(CallAction.HANGUP, null));
    }
}
