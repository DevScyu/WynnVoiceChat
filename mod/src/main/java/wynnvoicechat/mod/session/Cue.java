package wynnvoicechat.mod.session;

import wynnvoicechat.protocol.CallStateKind;

/**
 * One audible cue each, named after its sound event in {@code assets/wynnvoicechat/sounds.json} (which must also mark
 * the loops {@code "stream": true}). A call cue ends whichever ring loop is running; the two loops then ring until stopped.
 */
public enum Cue {
    INCOMING("call.incoming", true),
    RINGBACK("call.ringback", true),
    CONNECTED("call.connected", false),
    ENDED("call.ended", false),
    DECLINED("call.declined", false),
    BUSY("call.busy", false),
    PEER_JOINED("peer.joined"),
    PEER_LEFT("peer.left"),
    MIC_MUTE("mic.mute"),
    MIC_UNMUTE("mic.unmute");

    public final String event;
    public final boolean call;
    public final boolean loop;

    Cue(String event, boolean loop) {
        this.event = event;
        this.call = true;
        this.loop = loop;
    }

    Cue(String event) {
        this.event = event;
        this.call = false;
        this.loop = false;
    }

    public static Cue forCall(CallStateKind state) {
        return switch (state) {
            case INCOMING -> INCOMING;
            case RINGING -> RINGBACK;
            case ACTIVE -> CONNECTED;
            case ENDED, NO_ANSWER, EXPIRED -> ENDED;
            case DECLINED -> DECLINED;
            case BUSY, DND -> BUSY;
        };
    }
}
