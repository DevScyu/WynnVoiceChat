package wynnvoice.mod.wynn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wynnvoice.mod.wynn.FriendsTracker.Event;

class FriendsTrackerTest {
    private static final String FIRST = "§a\uE008\uE002 ";
    private static final String NEXT = "§a\uE001 ";

    @Test
    void parsesFriendList() {
        assertEquals(new Event.Listed(List.of("Syaoran3", "bolyai", "ShadowCat118")),
                FriendsTracker.parse(FIRST + "§eScyu_'s friends (3): §aSyaoran3, bolyai, ShadowCat118"));
        assertEquals(new Event.Listed(List.of("Syaoran3")), FriendsTracker.parse(NEXT + "Scyu_'s friends (1): Syaoran3"));
    }

    @Test
    void parsesSoftWrappedFriendList() {
        String wrapped = FIRST + "Scyu_'s friends (5): §aA, B, C, \n\uDAFF\uDFFC\uE001\uDB00\uDC06 D, E";
        assertEquals(new Event.Listed(List.of("A", "B", "C", "D", "E")), FriendsTracker.parse(wrapped));
    }

    @Test
    void parsesEmptyListAndChangeMessages() {
        assertEquals(new Event.NoFriends(), FriendsTracker.parse(FIRST + "We couldn't find any friends."));
        assertEquals(new Event.NoFriendsHint(), FriendsTracker.parse(NEXT + "Try typing §6/friend add Username§e!"));
        assertEquals(new Event.Added("Syaoran3"), FriendsTracker.parse(FIRST + "Syaoran3 has been added to your friends!"));
        assertEquals(new Event.Removed("Syaoran3"), FriendsTracker.parse(FIRST + "Syaoran3 has been removed from your friends!"));
        assertNull(FriendsTracker.parse("§7[§r§8Syaoran3§r§7] §rhello"));
        assertNull(FriendsTracker.parse("§aSyaoran3§2 has logged into server §aEU16§2 as §aa Shaman"));
        assertNull(FriendsTracker.parse("§e\uE005\uE002 Party members: §bScyu_, and §fSyaoran3"));
    }

    private final List<String> requests = new ArrayList<>();
    private final List<String> changes = new ArrayList<>();
    private final FriendsTracker tracker = new FriendsTracker(() -> requests.add("friend list"),
            (action, names) -> changes.add(action + names.toString()));

    @Test
    void listResponseIsHiddenOnlyWhenRequested() {
        assertFalse(tracker.onChat(FIRST + "Scyu_'s friends (2): §aSyaoran3, bolyai", null));
        assertEquals(Set.of("Syaoran3", "bolyai"), tracker.friends());
        tracker.requestList();
        assertEquals(List.of("friend list"), requests);
        assertTrue(tracker.onChat(FIRST + "Scyu_'s friends (2): §aSyaoran3, bolyai", null));
        assertFalse(tracker.onChat(FIRST + "Scyu_'s friends (2): §aSyaoran3, bolyai", null), "only the first response is hidden");
        assertEquals(List.of("SET[Syaoran3, bolyai]", "SET[Syaoran3, bolyai]", "SET[Syaoran3, bolyai]"), changes);
    }

    @Test
    void emptyListResponseIsTwoHiddenLines() {
        tracker.onChat(FIRST + "Scyu_'s friends (1): §aSyaoran3", null);
        tracker.requestList();
        assertTrue(tracker.onChat(FIRST + "We couldn't find any friends.", null));
        assertTrue(tracker.onChat(NEXT + "Try typing §6/friend add Username§e!", null));
        assertFalse(tracker.onChat(NEXT + "Try typing §6/friend add Username§e!", null));
        assertTrue(tracker.friends().isEmpty());
        assertEquals(List.of("SET[Syaoran3]", "SET[]", "SET[]"), changes);
    }

    @Test
    void changeMessagesAreNeverHiddenAndSendIncrementalChanges() {
        assertFalse(tracker.onChat(FIRST + "Syaoran3 has been added to your friends!", null));
        assertFalse(tracker.onChat(FIRST + "bol has been added to your friends!", "bolyai"));
        assertFalse(tracker.onChat(FIRST + "Syaoran3 has been removed from your friends!", null));
        assertEquals(Set.of("bolyai"), tracker.friends());
        assertEquals(List.of("ADD[Syaoran3]", "ADD[bolyai]", "REMOVE[Syaoran3]"), changes);
        assertTrue(requests.isEmpty());
    }

    @Test
    void resetForgetsFriendsSilently() {
        tracker.onChat(FIRST + "Scyu_'s friends (1): §aSyaoran3", null);
        tracker.reset();
        assertTrue(tracker.friends().isEmpty());
        assertEquals(1, changes.size());
    }
}
