package wynnvoicechat.mod.wynn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IgnoreTrackerTest {
    private static final String ADDED = WynnChatTest.P1 + "Syaoran3 has been added to your ignore list!";
    private static final String REMOVED = WynnChatTest.P1 + "Syaoran3 has been removed from your ignore list!";

    private final IgnoreTracker tracker = new IgnoreTracker();

    @Test
    void parsesTheIgnoreListNotifications() {
        assertEquals("Syaoran3", IgnoreTracker.parse(ADDED));
        assertEquals("Syaoran3", IgnoreTracker.parse(REMOVED));
        assertNull(IgnoreTracker.parse(WynnChatTest.P1 + "Syaoran3 has been added to your friends!"));
        assertNull(IgnoreTracker.parse("Syaoran3 has been added to your ignore list!"), "player chat carries no prefix");
    }

    @Test
    void hidesOnlyTheResponseToOurCommandOnce() {
        assertFalse(tracker.onChat(ADDED, null));
        tracker.expect("syaoran3");
        assertFalse(tracker.onChat(WynnChatTest.P1 + "Carol has been added to your ignore list!", null));
        assertTrue(tracker.onChat(ADDED, null));
        assertFalse(tracker.onChat(ADDED, null));
        tracker.expect("Nick");
        assertTrue(tracker.onChat(REMOVED, "Nick"), "nicknamed player resolved through the hover real name");
    }
}
