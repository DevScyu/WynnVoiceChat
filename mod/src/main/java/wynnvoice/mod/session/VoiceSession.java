package wynnvoice.mod.session;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import wynnvoice.mod.svc.VoiceChatPayloads;
import wynnvoice.protocol.CallStateKind;
import wynnvoice.protocol.EndReason;
import wynnvoice.protocol.Packet;
import wynnvoice.protocol.Peer;
import wynnvoice.protocol.Relation;
import wynnvoice.protocol.ResultKind;
import wynnvoice.protocol.SocialAction;
import wynnvoice.protocol.SocialKind;
import wynnvoice.protocol.VoiceTier;

/**
 * Drives one relay voice session: joins after auth, hands the secret to Simple Voice Chat, keeps the
 * relay's view of position and instance current and mirrors the peer list into SVC player states.
 * Not thread-safe beyond {@link #setSvcDisabled}: call from the render thread.
 */
public final class VoiceSession {
    public interface Effects {
        void send(Packet packet);

        /** The relay ended the session for a reason other than a timeout; say so once. */
        void ended(String relayMessage);

        /** The relay answered a block, unblock or report request. */
        void result(Packet.Result result);

        /** The relay answered a block list request. */
        void blockList(List<String> names);

        /** The relay confirmed a block or unblock; mirror it in Wynncraft's ignore list. */
        void ignore(String player, boolean add);

        /** First peer list of a session: how many are on voice on this world and how many of them can hear us. */
        void joined(int onVoice, int canHear);

        void injectSecret(Packet.Secret secret);

        void injectStates(List<VoiceChatPayloads.State> states);

        /** Real players on the level other than ourselves: uuid to display name. */
        Map<UUID, String> otherPlayers();

        Set<String> party();

        Set<String> friends();

        /** Put us in the read-only SVC group mirroring the party, call or guild channel, or in none. */
        void injectGroup(UUID group);

        /** A friend call progressed; say so, with accept/decline controls for an incoming one. */
        void callState(Packet.CallState state);
    }

    public static final UUID PARTY_GROUP = UUID.fromString("57c1a711-0000-0000-0000-000000000001");
    public static final UUID GUILD_GROUP = UUID.fromString("57c1a711-0000-0000-0000-000000000002");
    public static final UUID CALL_GROUP = UUID.fromString("57c1a711-0000-0000-0000-000000000003");

    private final Effects effects;
    private VoiceTier tier;
    private boolean authenticated;
    private volatile boolean active;
    private UUID group;
    private boolean guildChannel;
    private boolean dnd;
    private String callPeer;
    private String instance = "";
    private String joinedInstance = "";
    private VoiceTier joinedTier;
    private volatile boolean svcDisabled;
    private Packet.Position lastPosition;
    private List<Peer> lastPeers = List.of();
    private boolean peersAnnounced;
    private final Map<ResultKind, String> pendingBlockTargets = new EnumMap<>(ResultKind.class);
    private EndReason lastRefusal;

    public VoiceSession(VoiceTier tier, Effects effects) {
        this.tier = tier;
        this.effects = effects;
    }

    public void setTier(VoiceTier tier) {
        if (tier == this.tier) return;
        this.tier = tier;
        pushUpdate();
    }

    public void setGuildChannel(boolean guildChannel) {
        if (guildChannel == this.guildChannel) return;
        this.guildChannel = guildChannel;
        pushUpdate();
        placeGroup();
        syncStates();
    }

    public void setDnd(boolean dnd) {
        if (dnd == this.dnd) return;
        this.dnd = dnd;
        pushUpdate();
    }

    public boolean isActive() {
        return active;
    }

    public List<Peer> lastPeers() {
        return lastPeers;
    }

    public void onAuthenticated(String world, String instance) {
        this.instance = instance;
        authenticated = true;
        effects.send(new Packet.World(world));
        sendIfAny(SocialKind.PARTY, effects.party());
        sendIfAny(SocialKind.FRIENDS, effects.friends());
        join();
    }

    private void sendIfAny(SocialKind kind, Set<String> names) {
        if (!names.isEmpty()) effects.send(new Packet.Social(kind, SocialAction.SET, List.copyOf(names)));
    }

    public void social(SocialKind kind, SocialAction action, List<String> names) {
        if (authenticated) effects.send(new Packet.Social(kind, action, names));
    }

    /** Block, unblock or report; the relay closes the connection on packets sent before auth, so refuse those. */
    public boolean request(Packet packet) {
        if (!authenticated) return false;
        if (packet instanceof Packet.Block block) pendingBlockTargets.put(block.blocked() ? ResultKind.BLOCK : ResultKind.UNBLOCK, block.targetName());
        effects.send(packet);
        return true;
    }

    private void join() {
        joinedInstance = instance;
        joinedTier = tier;
        effects.send(new Packet.Join(tier, instance));
    }

    public void onPacket(Packet packet) {
        switch (packet) {
            case Packet.Secret secret -> {
                active = true;
                lastPosition = null;
                lastPeers = List.of();
                peersAnnounced = false;
                group = null;
                callPeer = null;
                effects.injectSecret(secret);
                if (svcDisabled || guildChannel || dnd || !instance.equals(joinedInstance) || tier != joinedTier) pushUpdate();
            }
            case Packet.Ended ended -> onEnded(ended);
            case Packet.Result result -> {
                effects.result(result);
                String target = pendingBlockTargets.remove(result.kind());
                if (result.ok() && target != null) effects.ignore(target, result.kind() == ResultKind.BLOCK);
            }
            case Packet.BlockListResult blocks -> effects.blockList(blocks.names());
            case Packet.CallState state -> {
                effects.callState(state);
                if (state.state() == CallStateKind.ACTIVE) callPeer = state.peerName();
                else if (state.state() == CallStateKind.ENDED) callPeer = null;
                placeGroup();
                syncStates();
            }
            case Packet.Peers peers -> {
                lastPeers = peers.peers();
                if (!peersAnnounced) {
                    peersAnnounced = true;
                    if (!lastPeers.isEmpty()) effects.joined(lastPeers.size(), (int) lastPeers.stream().filter(Peer::reachable).count());
                }
                placeGroup();
                syncStates();
            }
            default -> {}
        }
    }

    private void onEnded(Packet.Ended ended) {
        active = false;
        if (ended.reason() == EndReason.TIMED_OUT) {
            join();
            return;
        }
        if (ended.reason() == lastRefusal) return;
        lastRefusal = ended.reason();
        effects.ended(ended.message());
    }

    public void onClosed() {
        authenticated = false;
        active = false;
    }

    public void updatePosition(float x, float y, float z) {
        if (!active) return;
        Packet.Position position = new Packet.Position(x, y, z);
        if (position.equals(lastPosition)) return;
        lastPosition = position;
        effects.send(position);
    }

    public void setInstance(String instance) {
        if (instance.equals(this.instance)) return;
        this.instance = instance;
        pushUpdate();
    }

    public void setSvcDisabled(boolean disabled) {
        svcDisabled = disabled;
        pushUpdate();
    }

    private void pushUpdate() {
        if (!active) return;
        effects.send(new Packet.Update(tier, instance, svcDisabled, guildChannel, dnd));
    }

    /** One SVC group at a time: the party while any party peer is listed, else a call, else the guild channel while any member can hear us. */
    private void placeGroup() {
        if (!active) return;
        UUID next = callPeer == null ? null : CALL_GROUP;
        for (Peer peer : lastPeers) {
            UUID candidate = groupOf(peer, guildChannel, callPeer);
            if (candidate == PARTY_GROUP) {
                next = PARTY_GROUP;
                break;
            }
            if (candidate != null && next == null) next = candidate;
        }
        if (next == group) return;
        group = next;
        effects.injectGroup(next);
    }

    /** SVC draws nothing for players it has no state for, so non-voice players get an explicit "disconnected" state. */
    public void syncStates() {
        if (!active) return;
        effects.injectStates(buildStates(lastPeers, effects.otherPlayers(), guildChannel, callPeer));
    }

    // ponytail: guild peers who left the channel but stay reachable nearby still show in the group; the protocol has no channel flag
    static UUID groupOf(Peer peer, boolean guildChannel, String callPeer) {
        if (peer.relation() == Relation.PARTY) return PARTY_GROUP;
        if (peer.name().equals(callPeer)) return CALL_GROUP;
        return guildChannel && peer.relation() == Relation.GUILD && peer.reachable() ? GUILD_GROUP : null;
    }

    public static List<VoiceChatPayloads.State> buildStates(List<Peer> peers, Map<UUID, String> others, boolean guildChannel, String callPeer) {
        List<VoiceChatPayloads.State> states = new ArrayList<>(peers.size() + others.size());
        for (Peer peer : peers) {
            states.add(new VoiceChatPayloads.State(peer.uuid(), peer.name(), peer.disabled() || !peer.reachable(), false, groupOf(peer, guildChannel, callPeer)));
        }
        for (Map.Entry<UUID, String> other : others.entrySet()) {
            UUID uuid = other.getKey();
            if (uuid.version() != 4 || peers.stream().anyMatch(peer -> peer.uuid().equals(uuid))) continue;
            states.add(new VoiceChatPayloads.State(uuid, other.getValue(), false, true, null));
        }
        return states;
    }
}
