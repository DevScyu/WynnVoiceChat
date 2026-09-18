package wynnvoice.mod.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import wynnvoice.mod.svc.VoiceChatPayloads;
import wynnvoice.protocol.EndReason;
import wynnvoice.protocol.Packet;
import wynnvoice.protocol.Peer;
import wynnvoice.protocol.Relation;
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

        void injectSecret(Packet.Secret secret);

        void injectStates(List<VoiceChatPayloads.State> states);

        /** Real players on the level other than ourselves: uuid to display name. */
        Map<UUID, String> otherPlayers();

        Set<String> party();

        Set<String> friends();

        /** Put us in (or take us out of) the read-only SVC group that mirrors the Wynncraft party. */
        void injectPartyGroup(boolean joined);
    }

    public static final UUID PARTY_GROUP = UUID.fromString("57c1a711-0000-0000-0000-000000000001");

    private final Effects effects;
    private VoiceTier tier;
    private boolean authenticated;
    private volatile boolean active;
    private boolean inPartyGroup;
    private String instance = "";
    private String joinedInstance = "";
    private VoiceTier joinedTier;
    private volatile boolean svcDisabled;
    private Packet.Position lastPosition;
    private List<Peer> lastPeers = List.of();
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

    public boolean isActive() {
        return active;
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
                inPartyGroup = false;
                effects.injectSecret(secret);
                if (svcDisabled || !instance.equals(joinedInstance) || tier != joinedTier) pushUpdate();
            }
            case Packet.Ended ended -> onEnded(ended);
            case Packet.Peers peers -> {
                lastPeers = peers.peers();
                boolean anyParty = lastPeers.stream().anyMatch(peer -> peer.relation() == Relation.PARTY);
                if (anyParty != inPartyGroup) {
                    inPartyGroup = anyParty;
                    effects.injectPartyGroup(anyParty);
                }
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
        effects.send(new Packet.Update(tier, instance, svcDisabled, false, false));
    }

    /** SVC draws nothing for players it has no state for, so non-voice players get an explicit "disconnected" state. */
    public void syncStates() {
        if (!active) return;
        effects.injectStates(buildStates(lastPeers, effects.otherPlayers()));
    }

    public static List<VoiceChatPayloads.State> buildStates(List<Peer> peers, Map<UUID, String> others) {
        List<VoiceChatPayloads.State> states = new ArrayList<>(peers.size() + others.size());
        for (Peer peer : peers) {
            UUID group = peer.relation() == Relation.PARTY ? PARTY_GROUP : null;
            states.add(new VoiceChatPayloads.State(peer.uuid(), peer.name(), peer.disabled() || !peer.reachable(), false, group));
        }
        for (Map.Entry<UUID, String> other : others.entrySet()) {
            UUID uuid = other.getKey();
            if (uuid.version() != 4 || peers.stream().anyMatch(peer -> peer.uuid().equals(uuid))) continue;
            states.add(new VoiceChatPayloads.State(uuid, other.getValue(), false, true, null));
        }
        return states;
    }
}
