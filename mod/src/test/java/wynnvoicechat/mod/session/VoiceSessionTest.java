package wynnvoicechat.mod.session;

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
import wynnvoicechat.mod.svc.VoiceChatPayloads;
import wynnvoicechat.protocol.CallAction;
import wynnvoicechat.protocol.CallStateKind;
import wynnvoicechat.protocol.EndReason;
import wynnvoicechat.protocol.Packet;
import wynnvoicechat.protocol.Peer;
import wynnvoicechat.protocol.Relation;
import wynnvoicechat.protocol.ResultKind;
import wynnvoicechat.protocol.SocialAction;
import wynnvoicechat.protocol.SocialKind;
import wynnvoicechat.protocol.VoiceTier;

class VoiceSessionTest {
    private static final Packet.Secret SECRET = new Packet.Secret(new byte[16], "voice.test", 24454, 32.0, 1000, VoiceTier.EVERYONE);
    private static final Packet.Secret CAPPED_SECRET = new Packet.Secret(new byte[16], "voice.test", 24454, 32.0, 1000, VoiceTier.FRIENDS_AND_GUILD);

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
    private final List<String> groupNames = new ArrayList<>();
    private final List<VoiceTier> maxTiers = new ArrayList<>();
    private final List<Packet.CallState> callStates = new ArrayList<>();
    private final List<String> cues = new ArrayList<>();
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
        public void maxTier(VoiceTier maxTier) {
            maxTiers.add(maxTier);
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
        public void injectGroup(UUID group, String name) {
            groups.add(group);
            groupNames.add(name);
        }

        @Override
        public void callState(Packet.CallState state) {
            callStates.add(state);
        }

        @Override
        public void sound(Cue cue) {
            cues.add(cue.name());
        }

        @Override
        public void silence() {
            cues.add("silence");
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
        Peer hearing = new Peer(UUID.randomUUID(), "H", false, Relation.NONE, true, false);
        Peer deaf = new Peer(UUID.randomUUID(), "D", false, Relation.NONE, false, false);
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
                new Peer(reachable, "R", false, Relation.NONE, true, false),
                new Peer(unreachable, "U", false, Relation.NONE, false, false))));

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
        session.onPacket(new Packet.Peers(List.of(new Peer(stranger, "S", false, Relation.NONE, true, false))));
        assertTrue(groups.isEmpty());

        session.onPacket(new Packet.Peers(List.of(
                new Peer(pal, "P", false, Relation.PARTY, true, false),
                new Peer(stranger, "S", false, Relation.NONE, true, false))));
        session.onPacket(new Packet.Peers(List.of(new Peer(pal, "P", false, Relation.PARTY, true, false))));
        assertEquals(List.of(VoiceSession.PARTY_GROUP), groups);
        assertEquals(List.of(
                new VoiceChatPayloads.State(pal, "P", false, false, VoiceSession.PARTY_GROUP),
                new VoiceChatPayloads.State(stranger, "S", false, false, null)), injectedStates.get(1));

        session.onPacket(new Packet.Peers(List.of()));
        assertEquals(Arrays.asList(VoiceSession.PARTY_GROUP, null), groups);

        session.onPacket(new Packet.Peers(List.of(new Peer(pal, "P", false, Relation.PARTY, true, false))));
        session.onPacket(new Packet.Ended(EndReason.TIMED_OUT, "timed out"));
        session.onPacket(SECRET);
        session.onPacket(new Packet.Peers(List.of(new Peer(pal, "P", false, Relation.PARTY, true, false))));
        assertEquals(Arrays.asList(VoiceSession.PARTY_GROUP, null, VoiceSession.PARTY_GROUP, VoiceSession.PARTY_GROUP), groups, "a fresh SVC connection starts outside the group");
    }

    @Test
    void dndIsSentInUpdatesOnceActive() {
        session.setDnd(true);
        assertTrue(sent.isEmpty());
        session.onAuthenticated("WC1", "");
        session.onPacket(SECRET);
        assertEquals(new Packet.Update(VoiceTier.EVERYONE, "", false, false, true), sent.get(sent.size() - 1));
        sent.clear();
        session.setDnd(true);
        assertTrue(sent.isEmpty(), "unchanged flag sends nothing");
        session.setDnd(false);
        assertEquals(List.of(new Packet.Update(VoiceTier.EVERYONE, "", false, false, false)), sent);
    }

    @Test
    void anActiveCallPlacesThePeerInTheCallGroupUntilItEndsAndPartyWins() {
        joined();
        UUID friend = UUID.randomUUID();
        Peer friendPeer = new Peer(friend, "F", false, Relation.FRIEND, true, false);
        session.onPacket(new Packet.Peers(List.of(friendPeer)));
        assertTrue(groups.isEmpty());

        session.onPacket(new Packet.CallState("F", CallStateKind.RINGING));
        assertTrue(groups.isEmpty(), "ringing is not a call yet");
        session.onPacket(new Packet.CallState("F", CallStateKind.ACTIVE));
        assertEquals(List.of(VoiceSession.CALL_GROUP), groups, "the group opens without waiting for peers");
        assertEquals(new VoiceChatPayloads.State(friend, "F", false, false, VoiceSession.CALL_GROUP), injectedStates.get(injectedStates.size() - 1).get(0));
        session.onPacket(new Packet.Peers(List.of(friendPeer, new Peer(UUID.randomUUID(), "P", false, Relation.PARTY, true, false))));
        assertEquals(List.of(VoiceSession.CALL_GROUP, VoiceSession.PARTY_GROUP), groups, "party wins");

        session.onPacket(new Packet.Peers(List.of(friendPeer)));
        session.onPacket(new Packet.CallState("F", CallStateKind.ENDED));
        assertEquals(Arrays.asList(VoiceSession.CALL_GROUP, VoiceSession.PARTY_GROUP, VoiceSession.CALL_GROUP, null), groups);
        assertEquals(new VoiceChatPayloads.State(friend, "F", false, false, null), injectedStates.get(injectedStates.size() - 1).get(0));
        assertEquals(List.of(
                new Packet.CallState("F", CallStateKind.RINGING),
                new Packet.CallState("F", CallStateKind.ACTIVE),
                new Packet.CallState("F", CallStateKind.ENDED)), callStates);

        session.onPacket(new Packet.CallState("F", CallStateKind.ACTIVE));
        session.onPacket(new Packet.Ended(EndReason.TIMED_OUT, "timed out"));
        session.onPacket(SECRET);
        session.onPacket(new Packet.Peers(List.of(friendPeer)));
        assertEquals(VoiceSession.CALL_GROUP, groups.get(4));
        assertEquals(5, groups.size(), "a fresh SVC connection forgets the call; the relay ended it");
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
        Peer matePeer = new Peer(mate, "M", false, Relation.GUILD, true, true);
        Peer offPeer = new Peer(off, "O", false, Relation.GUILD, true, false);
        session.onPacket(new Packet.Peers(List.of(matePeer, offPeer)));
        assertEquals(List.of(VoiceSession.GUILD_GROUP), groups);
        assertEquals(List.of("Guild"), groupNames, "no prefix yet");
        assertEquals(List.of(
                new VoiceChatPayloads.State(mate, "M", false, false, VoiceSession.GUILD_GROUP),
                new VoiceChatPayloads.State(off, "O", false, false, null)), injectedStates.get(0), "a mate with the channel off stays out of the group even when audible");

        session.onPacket(new Packet.Peers(List.of(matePeer, new Peer(pal, "P", false, Relation.PARTY, true, false))));
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

    @Test
    void guildGroupIsNamedAfterThePrefixAndRenamedWhenItArrivesLate() {
        session.setGuildChannel(true);
        joined();
        Peer mate = new Peer(UUID.randomUUID(), "M", false, Relation.GUILD, true, true);
        session.onPacket(new Packet.Peers(List.of(mate)));
        assertEquals(List.of("Guild"), groupNames);

        session.onPacket(new Packet.Guild("ABC"));
        assertEquals(List.of(VoiceSession.GUILD_GROUP, VoiceSession.GUILD_GROUP), groups, "re-added under the new name");
        assertEquals(List.of("Guild", "[ABC]"), groupNames);

        session.onPacket(new Packet.Peers(List.of()));
        session.onPacket(new Packet.Guild("XYZ"));
        assertEquals(3, groups.size(), "not in the guild group: nothing to rename");
        session.onPacket(new Packet.Peers(List.of(mate)));
        assertEquals("[XYZ]", groupNames.get(groupNames.size() - 1));

        session.onPacket(new Packet.Guild(""));
        assertEquals("Guild", groupNames.get(groupNames.size() - 1), "left the guild: back to the fallback");
    }

    @Test
    void relayCapIsRememberedAndReportedOnceWhenItChanges() {
        assertEquals(VoiceTier.EVERYONE, session.maxTier(), "assumed uncapped before the first Secret");
        session.onAuthenticated("WC1", "");
        session.onPacket(SECRET);
        assertTrue(maxTiers.isEmpty(), "an uncapped relay changes nothing");

        session.onPacket(new Packet.Ended(EndReason.TIMED_OUT, "timed out"));
        session.onPacket(CAPPED_SECRET);
        assertEquals(VoiceTier.FRIENDS_AND_GUILD, session.maxTier());
        assertEquals(List.of(VoiceTier.FRIENDS_AND_GUILD), maxTiers);

        session.onPacket(new Packet.Ended(EndReason.TIMED_OUT, "timed out"));
        session.onPacket(CAPPED_SECRET);
        assertEquals(List.of(VoiceTier.FRIENDS_AND_GUILD), maxTiers, "the same cap again is not news");

        session.onPacket(new Packet.Ended(EndReason.TIMED_OUT, "timed out"));
        session.onPacket(SECRET);
        assertEquals(List.of(VoiceTier.FRIENDS_AND_GUILD, VoiceTier.EVERYONE), maxTiers, "a lifted cap is");
    }

    @Test
    void callStatesPlayTheirCueAndTheLoopStopsOnAnyOutcome() {
        joined();
        session.onPacket(new Packet.CallState("F", CallStateKind.RINGING));
        session.onPacket(new Packet.CallState("F", CallStateKind.BUSY));
        session.onPacket(new Packet.CallState("F", CallStateKind.RINGING));
        session.onPacket(new Packet.CallState("F", CallStateKind.DND));
        session.onPacket(new Packet.CallState("F", CallStateKind.RINGING));
        session.onPacket(new Packet.CallState("F", CallStateKind.NO_ANSWER));
        session.onPacket(new Packet.CallState("F", CallStateKind.RINGING));
        session.onPacket(new Packet.CallState("F", CallStateKind.DECLINED));
        session.onPacket(new Packet.CallState("F", CallStateKind.INCOMING));
        session.onPacket(new Packet.CallState("F", CallStateKind.ACTIVE));
        session.onPacket(new Packet.CallState("F", CallStateKind.ENDED));
        assertEquals(List.of("RINGBACK", "BUSY", "RINGBACK", "BUSY", "RINGBACK", "ENDED", "RINGBACK", "DECLINED", "INCOMING", "CONNECTED", "ENDED"), cues);
    }

    @Test
    void staleCallAnswersAndSessionEndsSilenceTheLoop() {
        joined();
        session.onPacket(new Packet.CallState("F", CallStateKind.INCOMING));
        session.request(new Packet.Call("G", CallAction.INVITE));
        session.onPacket(new Packet.Result(ResultKind.CALL, false, "Hang up or answer your current call first"));
        session.request(new Packet.Call("", CallAction.ACCEPT));
        session.onPacket(new Packet.Result(ResultKind.CALL, false, "Nobody is calling you"));
        session.onPacket(new Packet.Result(ResultKind.BLOCK, false, "Unknown player"));
        session.onPacket(new Packet.CallState("F", CallStateKind.INCOMING));
        session.onPacket(new Packet.Ended(EndReason.TIMED_OUT, "timed out"));
        session.onPacket(SECRET);
        session.onPacket(new Packet.CallState("F", CallStateKind.INCOMING));
        session.onClosed();
        assertEquals(List.of("INCOMING", "silence", "INCOMING", "silence", "INCOMING", "silence"), cues);
    }

    @Test
    void peerArrivalsAndDeparturesPlayOncePerPacketAfterTheFirst() {
        joined();
        Peer a = new Peer(UUID.randomUUID(), "A", false, Relation.NONE, true, false);
        Peer b = new Peer(UUID.randomUUID(), "B", false, Relation.NONE, true, false);
        Peer c = new Peer(UUID.randomUUID(), "C", false, Relation.NONE, true, false);
        Peer deaf = new Peer(UUID.randomUUID(), "D", false, Relation.NONE, false, false);
        session.onPacket(new Packet.Peers(List.of(a)));
        assertTrue(cues.isEmpty(), "the first roster is not an arrival");
        session.onPacket(new Packet.Peers(List.of(a, b, c)));
        assertEquals(List.of("PEER_JOINED"), cues, "two arrivals, one cue");
        session.onPacket(new Packet.Peers(List.of(a, b, c)));
        assertEquals(List.of("PEER_JOINED"), cues, "unchanged roster is silent");
        session.onPacket(new Packet.Peers(List.of(b, deaf)));
        assertEquals(List.of("PEER_JOINED", "PEER_LEFT"), cues, "a peer who cannot hear us is not an arrival");
        session.onPacket(new Packet.Peers(List.of(a, deaf)));
        assertEquals(List.of("PEER_JOINED", "PEER_LEFT", "PEER_JOINED", "PEER_LEFT"), cues, "join and leave in one packet play both once");

        session.onPacket(new Packet.Ended(EndReason.TIMED_OUT, "timed out"));
        session.onPacket(SECRET);
        cues.clear();
        session.onPacket(new Packet.Peers(List.of(a, b)));
        assertTrue(cues.isEmpty(), "a fresh session starts over");
    }
}
