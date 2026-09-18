package wynnvoice.mod.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import wynnvoice.mod.svc.VoiceChatPayloads;
import wynnvoice.protocol.EndReason;
import wynnvoice.protocol.Packet;
import wynnvoice.protocol.Peer;
import wynnvoice.protocol.Relation;
import wynnvoice.protocol.ResultKind;
import wynnvoice.protocol.SocialAction;
import wynnvoice.protocol.SocialKind;
import wynnvoice.protocol.VoiceTier;

class VoiceSessionTest {
    private static final Packet.Secret SECRET = new Packet.Secret(new byte[16], "voice.test", 24454, 32.0, 1000);

    private final List<Packet> sent = new ArrayList<>();
    private final List<String> ended = new ArrayList<>();
    private final List<Packet.Result> results = new ArrayList<>();
    private final List<List<String>> blockLists = new ArrayList<>();
    private final List<String> ignores = new ArrayList<>();
    private final List<int[]> joinedLines = new ArrayList<>();
    private final List<Packet.Secret> injectedSecrets = new ArrayList<>();
    private final List<List<VoiceChatPayloads.State>> injectedStates = new ArrayList<>();
    private final Map<UUID, String> others = new LinkedHashMap<>();
    private final List<UUID> groups = new ArrayList<>();
    private Set<String> party = Set.of();
    private Set<String> friends = Set.of();

    private final VoiceSession.Effects effects = new VoiceSession.Effects() {
        @Override
        public void send(Packet packet) {
            sent.add(packet);
        }

        @Override
        public void ended(String relayMessage) {
            ended.add(relayMessage);
        }

        @Override
        public void result(Packet.Result result) {
            results.add(result);
        }

        @Override
        public void blockList(List<String> names) {
            blockLists.add(names);
        }

        @Override
        public void ignore(String player, boolean add) {
            ignores.add((add ? "add " : "remove ") + player);
        }

        @Override
        public void joined(int onVoice, int canHear) {
            joinedLines.add(new int[] {onVoice, canHear});
        }

        @Override
        public void injectSecret(Packet.Secret secret) {
            injectedSecrets.add(secret);
        }

        @Override
        public void injectStates(List<VoiceChatPayloads.State> states) {
            injectedStates.add(states);
        }

        @Override
        public Map<UUID, String> otherPlayers() {
            return others;
        }

        @Override
        public Set<String> party() {
            return party;
        }

        @Override
        public Set<String> friends() {
            return friends;
        }

        @Override
        public void injectGroup(UUID group) {
            groups.add(group);
        }
    };
    private final VoiceSession session = new VoiceSession(VoiceTier.EVERYONE, effects);

    private void joined() {
        session.onAuthenticated("WC1", "");
        session.onPacket(SECRET);
        sent.clear();
    }

    @Test
    void authenticatedSendsWorldThenJoin() {
        session.onAuthenticated("WC1", "housing:Me");
        assertEquals(List.of(new Packet.World("WC1"), new Packet.Join(VoiceTier.EVERYONE, "housing:Me")), sent);
        assertFalse(session.isActive());
    }

    @Test
    void secretActivatesAndIsInjected() {
        session.onAuthenticated("WC1", "");
        session.onPacket(SECRET);
        assertTrue(session.isActive());
        assertEquals(List.of(SECRET), injectedSecrets);
    }

    @Test
    void timedOutRejoinsOtherEndsSayOnce() {
        joined();
        session.onPacket(new Packet.Ended(EndReason.TIMED_OUT, "timed out"));
        assertFalse(session.isActive());
        assertEquals(List.of(new Packet.Join(VoiceTier.EVERYONE, "")), sent);
        assertTrue(ended.isEmpty());

        session.onPacket(SECRET);
        sent.clear();
        session.onPacket(new Packet.Ended(EndReason.DISABLED, "relay off"));
        session.onPacket(new Packet.Ended(EndReason.DISABLED, "relay off"));
        assertTrue(sent.isEmpty());
        assertEquals(List.of("relay off"), ended);
    }

    @Test
    void requestsGoOutOnlyWhileAuthenticatedAndResultsAreSurfaced() {
        assertFalse(session.request(new Packet.Block("Bob", true)));
        assertTrue(sent.isEmpty());
        session.onAuthenticated("WC1", "");
        sent.clear();
        assertTrue(session.request(new Packet.Report("Bob", "spam")));
        assertEquals(List.of(new Packet.Report("Bob", "spam")), sent);

        Packet.Result result = new Packet.Result(ResultKind.REPORT, true, "Report #1 filed");
        session.onPacket(result);
        assertEquals(List.of(result), results);

        session.onClosed();
        assertFalse(session.request(new Packet.Block("Bob", false)));
    }

    @Test
    void confirmedBlockIgnoresOnceAndFailedBlockDoesNot() {
        joined();
        session.request(new Packet.Block("Bob", true));
        assertTrue(ignores.isEmpty());
        session.onPacket(new Packet.Result(ResultKind.BLOCK, false, "Unknown player Bob"));
        assertTrue(ignores.isEmpty());

        session.request(new Packet.Block("Bob", true));
        session.onPacket(new Packet.Result(ResultKind.BLOCK, true, "Blocked Bob"));
        session.onPacket(new Packet.Result(ResultKind.BLOCK, true, "Blocked Bob"));
        assertEquals(List.of("add Bob"), ignores);

        session.request(new Packet.Block("Bob", false));
        session.onPacket(new Packet.Result(ResultKind.REPORT, true, "Report #1 filed"));
        session.onPacket(new Packet.Result(ResultKind.UNBLOCK, true, "Unblocked Bob"));
        assertEquals(List.of("add Bob", "remove Bob"), ignores);
    }

    @Test
    void blockListResultIsSurfaced() {
        joined();
        session.onPacket(new Packet.BlockListResult(List.of("Bob")));
        assertEquals(List.of(List.of("Bob")), blockLists);
    }

    @Test
    void firstPeersAfterActivationAnnouncesCountsOnceUnlessEmpty() {
        joined();
        Peer hearing = new Peer(UUID.randomUUID(), "H", false, Relation.NONE, true);
        Peer deaf = new Peer(UUID.randomUUID(), "D", false, Relation.NONE, false);
        session.onPacket(new Packet.Peers(List.of()));
        session.onPacket(new Packet.Peers(List.of(hearing)));
        assertTrue(joinedLines.isEmpty(), "nobody here at join: no line, also not for later arrivals");

        session.onPacket(new Packet.Ended(EndReason.TIMED_OUT, "timed out"));
        session.onPacket(SECRET);
        assertEquals(List.of(), session.lastPeers(), "stale roster cleared on a fresh session");
        session.onPacket(new Packet.Peers(List.of(hearing, deaf)));
        session.onPacket(new Packet.Peers(List.of(hearing)));
        assertEquals(1, joinedLines.size());
        assertEquals(2, joinedLines.get(0)[0]);
        assertEquals(1, joinedLines.get(0)[1]);
        assertEquals(List.of(hearing), session.lastPeers());
    }

    @Test
    void positionIsSentOnlyWhenActiveAndChanged() {
        session.updatePosition(1, 2, 3);
        assertTrue(sent.isEmpty());
        joined();
        session.updatePosition(1, 2, 3);
        session.updatePosition(1, 2, 3);
        session.updatePosition(1, 2, 4);
        assertEquals(List.of(new Packet.Position(1, 2, 3), new Packet.Position(1, 2, 4)), sent);
    }

    @Test
    void instanceAndSvcDisabledChangesSendUpdate() {
        session.setInstance("housing:X");
        assertTrue(sent.isEmpty());
        joined();
        session.setInstance("");
        assertTrue(sent.isEmpty(), "unchanged instance");
        session.setInstance("housing:X");
        session.setSvcDisabled(true);
        assertEquals(List.of(
                new Packet.Update(VoiceTier.EVERYONE, "housing:X", false, false, false),
                new Packet.Update(VoiceTier.EVERYONE, "housing:X", true, false, false)), sent);
    }

    @Test
    void tierChangeSendsUpdateImmediatelyAndJoinsWithTheNewTier() {
        session.setTier(VoiceTier.PARTY);
        assertTrue(sent.isEmpty());
        joined();
        session.setTier(VoiceTier.PARTY);
        assertTrue(sent.isEmpty(), "unchanged tier");
        session.setTier(VoiceTier.FRIENDS_AND_GUILD);
        assertEquals(List.of(new Packet.Update(VoiceTier.FRIENDS_AND_GUILD, "", false, false, false)), sent);

        sent.clear();
        session.onPacket(new Packet.Ended(EndReason.TIMED_OUT, "timed out"));
        assertEquals(List.of(new Packet.Join(VoiceTier.FRIENDS_AND_GUILD, "")), sent);

        session.setTier(VoiceTier.EVERYONE);
        session.onPacket(SECRET);
        assertEquals(new Packet.Update(VoiceTier.EVERYONE, "", false, false, false), sent.get(sent.size() - 1), "changed between Join and Secret");
    }

    @Test
    void svcDisabledBeforeJoinIsForwardedOnceActive() {
        session.setSvcDisabled(true);
        session.onAuthenticated("WC1", "");
        session.onPacket(SECRET);
        assertEquals(new Packet.Update(VoiceTier.EVERYONE, "", true, false, false), sent.get(sent.size() - 1));
    }

    @Test
    void instanceChangedBetweenJoinAndSecretIsPushedOnceActive() {
        session.onAuthenticated("WC1", "");
        session.setInstance("housing:X");
        session.onPacket(SECRET);
        assertEquals(new Packet.Update(VoiceTier.EVERYONE, "housing:X", false, false, false), sent.get(sent.size() - 1));
    }

    @Test
    void peersDriveStatesAndNpcsAreIgnored() {
        joined();
        UUID reachable = UUID.randomUUID();
        UUID unreachable = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID npc = UUID.nameUUIDFromBytes("npc".getBytes());
        others.put(reachable, "R");
        others.put(stranger, "S");
        others.put(npc, "Shopkeeper");

        session.onPacket(new Packet.Peers(List.of(
                new Peer(reachable, "R", false, Relation.NONE, true),
                new Peer(unreachable, "U", false, Relation.NONE, false))));

        assertEquals(List.of(
                new VoiceChatPayloads.State(reachable, "R", false, false, null),
                new VoiceChatPayloads.State(unreachable, "U", true, false, null),
                new VoiceChatPayloads.State(stranger, "S", false, true, null)), injectedStates.get(0));
    }

    @Test
    void closedDeactivatesWithoutRejoin() {
        joined();
        session.onClosed();
        assertFalse(session.isActive());
        session.updatePosition(1, 1, 1);
        assertTrue(sent.isEmpty());
    }

    @Test
    void socialListsAreSentAfterAuthAndChangesOnlyWhileAuthenticated() {
        session.social(SocialKind.PARTY, SocialAction.ADD, List.of("Early"));
        session.onAuthenticated("WC1", "");
        assertEquals(List.of(new Packet.World("WC1"), new Packet.Join(VoiceTier.EVERYONE, "")), sent, "empty lists are not sent");

        party = new LinkedHashSet<>(List.of("Me", "Pal"));
        friends = Set.of("Buddy");
        VoiceSession next = new VoiceSession(VoiceTier.PARTY, effects);
        sent.clear();
        next.onAuthenticated("WC1", "");
        next.social(SocialKind.PARTY, SocialAction.REMOVE, List.of("Pal"));
        assertEquals(List.of(
                new Packet.World("WC1"),
                new Packet.Social(SocialKind.PARTY, SocialAction.SET, List.of("Me", "Pal")),
                new Packet.Social(SocialKind.FRIENDS, SocialAction.SET, List.of("Buddy")),
                new Packet.Join(VoiceTier.PARTY, ""),
                new Packet.Social(SocialKind.PARTY, SocialAction.REMOVE, List.of("Pal"))), sent);

        next.onClosed();
        sent.clear();
        next.social(SocialKind.PARTY, SocialAction.ADD, List.of("Late"));
        assertTrue(sent.isEmpty());
    }

    @Test
    void partyPeersJoinTheReadOnlyGroupAndLeaveItWhenGone() {
        joined();
        UUID pal = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        session.onPacket(new Packet.Peers(List.of(new Peer(stranger, "S", false, Relation.NONE, true))));
        assertTrue(groups.isEmpty());

        session.onPacket(new Packet.Peers(List.of(
                new Peer(pal, "P", false, Relation.PARTY, true),
                new Peer(stranger, "S", false, Relation.NONE, true))));
        session.onPacket(new Packet.Peers(List.of(new Peer(pal, "P", false, Relation.PARTY, true))));
        assertEquals(List.of(VoiceSession.PARTY_GROUP), groups);
        assertEquals(List.of(
                new VoiceChatPayloads.State(pal, "P", false, false, VoiceSession.PARTY_GROUP),
                new VoiceChatPayloads.State(stranger, "S", false, false, null)), injectedStates.get(1));

        session.onPacket(new Packet.Peers(List.of()));
        assertEquals(Arrays.asList(VoiceSession.PARTY_GROUP, null), groups);

        session.onPacket(new Packet.Peers(List.of(new Peer(pal, "P", false, Relation.PARTY, true))));
        session.onPacket(new Packet.Ended(EndReason.TIMED_OUT, "timed out"));
        session.onPacket(SECRET);
        session.onPacket(new Packet.Peers(List.of(new Peer(pal, "P", false, Relation.PARTY, true))));
        assertEquals(Arrays.asList(VoiceSession.PARTY_GROUP, null, VoiceSession.PARTY_GROUP, VoiceSession.PARTY_GROUP), groups, "a fresh SVC connection starts outside the group");
    }

    @Test
    void guildChannelIsSentAndPlacesReachableGuildPeersInTheGuildGroupUnlessInAParty() {
        session.setGuildChannel(true);
        assertTrue(sent.isEmpty());
        session.onAuthenticated("WC1", "");
        session.onPacket(SECRET);
        assertEquals(new Packet.Update(VoiceTier.EVERYONE, "", false, true, false), sent.get(sent.size() - 1), "guild channel is pushed once active");
        sent.clear();

        UUID mate = UUID.randomUUID();
        UUID off = UUID.randomUUID();
        UUID pal = UUID.randomUUID();
        Peer matePeer = new Peer(mate, "M", false, Relation.GUILD, true);
        Peer offPeer = new Peer(off, "O", false, Relation.GUILD, false);
        session.onPacket(new Packet.Peers(List.of(matePeer, offPeer)));
        assertEquals(List.of(VoiceSession.GUILD_GROUP), groups);
        assertEquals(List.of(
                new VoiceChatPayloads.State(mate, "M", false, false, VoiceSession.GUILD_GROUP),
                new VoiceChatPayloads.State(off, "O", true, false, null)), injectedStates.get(0));

        session.onPacket(new Packet.Peers(List.of(matePeer, new Peer(pal, "P", false, Relation.PARTY, true))));
        assertEquals(List.of(VoiceSession.GUILD_GROUP, VoiceSession.PARTY_GROUP), groups, "party wins");
        assertEquals(VoiceSession.GUILD_GROUP, injectedStates.get(1).get(0).group(), "guild mates still show in their group");

        session.onPacket(new Packet.Peers(List.of(matePeer)));
        session.setGuildChannel(false);
        assertEquals(List.of(new Packet.Update(VoiceTier.EVERYONE, "", false, false, false)), sent);
        assertEquals(Arrays.asList(VoiceSession.GUILD_GROUP, VoiceSession.PARTY_GROUP, VoiceSession.GUILD_GROUP, null), groups, "toggling off leaves the group without waiting for peers");
        assertEquals(new VoiceChatPayloads.State(mate, "M", false, false, null), injectedStates.get(injectedStates.size() - 1).get(0));
        session.setGuildChannel(false);
        assertEquals(1, sent.size(), "unchanged toggle sends nothing");
    }
}
