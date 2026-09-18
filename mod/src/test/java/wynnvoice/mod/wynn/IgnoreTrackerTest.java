package wynnvoice.mod.wynn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IgnoreTrackerTest {
    private long now = 10_000;
    private final IgnoreTracker tracker = new IgnoreTracker(() -> now);

    @Test
    void matchesLinesNamingThePlayerAndIgnore() {
        assertTrue(IgnoreTracker.matches("§e §aBob§e has been added to your ignore list!", "bob", null));
        assertTrue(IgnoreTracker.matches("You are no longer ignoring Bob.", "Bob", null));
        assertTrue(IgnoreTracker.matches("Nickname is now ignored.", "Bob", "Bob"));
        assertFalse(IgnoreTracker.matches("Bob has been added to your friends!", "Bob", null));
        assertFalse(IgnoreTracker.matches("Carol is now ignored.", "Bob", null));
    }

    @Test
    void hidesOnlyWithinTheWindowAfterTheCommand() {
        assertFalse(tracker.onChat("Bob is now ignored.", null));
        tracker.expect("Bob");
        assertTrue(tracker.onChat("Bob is now ignored.", null));
        assertTrue(tracker.onChat("You will no longer see Bob's messages, ignore list updated.", null));
        assertFalse(tracker.onChat("Bob: hello", null));
        now += IgnoreTracker.WINDOW_MS + 1;
        assertFalse(tracker.onChat("Bob is now ignored.", null));
    }
}
