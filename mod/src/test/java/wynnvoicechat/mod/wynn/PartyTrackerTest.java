package wynnvoicechat.mod.wynn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wynnvoicechat.mod.wynn.PartyTracker.Event;
import wynnvoicechat.protocol.SocialAction;

class PartyTrackerTest {
    private static final String FIRST = WynnChatTest.P1;
    private static final String NEXT = WynnChatTest.P2;

    @Test
    void parsesPartyListWithOxfordComma() {
        assertEquals(new Event.Listed(List.of("Scyu_")), PartyTracker.parse(NEXT + "Party members: Scyu_"));
        assertEquals(new Event.Listed(List.of("Scyu_", "Syaoran3")), PartyTracker.parse(NEXT + "Party members: Scyu_, and Syaoran3"));
        assertEquals(new Event.Listed(List.of("e_z_x", "Saunt", "Dopeul", "IM_NoOne", "6bccy", "ShadowCat117")),
                PartyTracker.parse(FIRST + "Party members: §be_z_x, §fSaunt, Dopeul, IM_NoOne,§e §f6bccy, and ShadowCat117"));
    }

    @Test
    void parsesSoftWrappedPartyList() {
        String wrapped = NEXT + "Party members: A, B, C,\n" + NEXT + "D, and E";
        assertEquals(new Event.Listed(List.of("A", "B", "C", "D", "E")), PartyTracker.parse(wrapped));
    }

    @Test
    void parsesMembershipMessages() {
        assertEquals(new Event.NotInParty(), PartyTracker.parse(FIRST + "You must be in a party to use this."));
        assertEquals(new Event.Created(), PartyTracker.parse(FIRST + "You have successfully created a party."));
        assertEquals(new Event.Joined("Syaoran3"), PartyTracker.parse(FIRST + "Syaoran3 has joined your party, say hello!"));
        assertEquals(new Event.Joined("Syaoran3"), PartyTracker.parse(NEXT + "§eSyaoran3 has joined your party, say hello!"));
        assertEquals(new Event.Left("Syaoran3"), PartyTracker.parse(FIRST + "Syaoran3 has left the party!"));
        assertEquals(new Event.Left("Syaoran3"), PartyTracker.parse(FIRST + "Syaoran3 has been kicked from the party!"));
        assertEquals(new Event.SelfLeft(), PartyTracker.parse(FIRST + "You have left your current party"));
        assertEquals(new Event.SelfLeft(), PartyTracker.parse(FIRST + "You have been kicked from your party"));
        assertEquals(new Event.SelfLeft(), PartyTracker.parse(FIRST + "Your party has been disbanded"));
        assertEquals(new Event.Restored(), PartyTracker.parse(FIRST + "Your previous party was restored"));
        assertNull(PartyTracker.parse("§7[§r§8Syaoran3§r§7] §rhello"));
        assertNull(PartyTracker.parse(NEXT + "Syaoran3 is now the Party Leader!"));
        assertNull(PartyTracker.parse(NEXT + "You are now the leader of this party! Type /party for a\n" + NEXT + "list of commands."));
        assertNull(PartyTracker.parse("\n      §eYou have been invited to join Syaoran3's party!\n"));
        assertNull(PartyTracker.parse(NEXT + "You have invited the player to the party."));
    }

    private final List<String> requests = new ArrayList<>();
    private final List<String> changes = new ArrayList<>();
    private final PartyTracker tracker = new PartyTracker("Scyu_", () -> requests.add("party list"),
            (action, names) -> changes.add(action + names.toString()));

    @Test
    void listResponseIsHiddenOnlyWhenRequested() {
        assertFalse(tracker.onChat(NEXT + "Party members: §bScyu_, and §fSyaoran3", null));
        assertEquals(Set.of("Scyu_", "Syaoran3"), tracker.members());
        tracker.requestList();
        assertEquals(List.of("party list"), requests);
        assertTrue(tracker.onChat(NEXT + "Party members: §bScyu_, and §fSyaoran3", null));
        assertFalse(tracker.onChat(NEXT + "Party members: §bScyu_, and §fSyaoran3", null), "only the first response is hidden");
        assertEquals(List.of("SET[Scyu_, Syaoran3]", "SET[Scyu_, Syaoran3]", "SET[Scyu_, Syaoran3]"), changes);
    }

    @Test
    void notInPartyResponseClearsAndIsHidden() {
        tracker.onChat(NEXT + "Party members: §bScyu_, and §fSyaoran3", null);
        tracker.requestList();
        assertTrue(tracker.onChat(FIRST + "You must be in a party to use this.", null));
        assertTrue(tracker.members().isEmpty());
        assertEquals("SET[]", changes.get(changes.size() - 1));
    }

    @Test
    void membershipMessagesAreNeverHiddenAndSendIncrementalChanges() {
        assertFalse(tracker.onChat(FIRST + "You have successfully created a party.", null));
        assertFalse(tracker.onChat(NEXT + "Syaoran3 has joined your party, say hello!", null));
        assertFalse(tracker.onChat(NEXT + "Other has joined your party, say hello!", null));
        assertFalse(tracker.onChat(FIRST + "Other has been kicked from the party!", null));
        assertEquals(Set.of("Scyu_", "Syaoran3"), tracker.members());
        assertFalse(tracker.onChat(FIRST + "Your party has been disbanded", null));
        assertTrue(tracker.members().isEmpty());
        assertEquals(List.of("SET[Scyu_]", "ADD[Syaoran3]", "ADD[Other]", "REMOVE[Other]", "SET[]"), changes);
        assertTrue(requests.isEmpty());
    }

    @Test
    void joiningAnotherPartyOrRestoringReRequestsTheList() {
        tracker.onChat(NEXT + "Scyu_ has joined your party, say hello!", null);
        tracker.onChat(FIRST + "Your previous party was restored", null);
        assertEquals(List.of("party list", "party list"), requests);
        assertTrue(changes.isEmpty());
    }

    @Test
    void nicknamedPlayersUseTheirRealName() {
        tracker.onChat(NEXT + "bol has joined your party, say hello!", "bolyai");
        assertEquals(Set.of("bolyai"), tracker.members());
        assertEquals(List.of("ADD[bolyai]"), changes);
    }

    @Test
    void resetForgetsMembersSilently() {
        tracker.onChat(NEXT + "Party members: §bScyu_, and §fSyaoran3", null);
        tracker.reset();
        assertTrue(tracker.members().isEmpty());
        assertEquals(1, changes.size());
    }

    @Test
    void withoutACommandTheListIsParsedButNeverHidden() {
        PartyTracker passive = new PartyTracker("Scyu_", null, (action, names) -> changes.add(action + names.toString()));
        passive.requestList();
        assertFalse(passive.onChat(NEXT + "Party members: Scyu_, and Syaoran3", null));
        assertEquals(Set.of("Scyu_", "Syaoran3"), passive.members());
        assertEquals(List.of("SET[Scyu_, Syaoran3]"), changes);
    }
}
