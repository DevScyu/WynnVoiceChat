package wynnvoicechat.mod.wynn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import wynnvoicechat.mod.wynn.WorldTracker.State;

class WorldTrackerTest {
    @Test
    void globalEntryIsAWorld() {
        assertEquals(new State("WC12", ""), WorldTracker.next(State.NONE, "§f  §lGlobal [WC12]"));
        assertEquals(new State("EU3", ""), WorldTracker.next(State.NONE, "  Global [EU3]"));
    }

    @Test
    void housingEntryKeepsCurrentWorld() {
        State onWorld = new State("WC12", "");
        State housing = WorldTracker.next(onWorld, "§f  §lScyu_");
        assertEquals(new State("WC12", "Scyu_"), housing);
        assertEquals("housing:Scyu_", housing.instance());
        assertEquals("WC??", WorldTracker.next(State.NONE, "§f  §lScyu_").world());
    }

    @Test
    void leavingHousingReturnsToWorld() {
        State housing = new State("WC12", "Scyu_");
        assertEquals(new State("WC12", ""), WorldTracker.next(housing, "§f  §lGlobal [WC12]"));
    }

    @Test
    void absentEntryIsNotOnAWorld() {
        assertEquals(State.NONE, WorldTracker.next(new State("WC12", ""), null));
        assertFalse(State.NONE.onWorld());
    }

    @Test
    void unrecognisedEntryLeavesStateAlone() {
        State onWorld = new State("WC12", "");
        assertEquals(onWorld, WorldTracker.next(onWorld, "\n§6§l play.wynncraft.com \n"));
        assertEquals(onWorld, WorldTracker.next(onWorld, "§f  §lThis housing name is far too long to be real, really"));
    }

    @Test
    void updateReportsChanges() {
        WorldTracker tracker = new WorldTracker();
        assertTrue(tracker.update("§f  §lGlobal [WC1]"));
        assertFalse(tracker.update("§f  §lGlobal [WC1]"));
        assertTrue(tracker.update(null));
        assertEquals(State.NONE, tracker.state());
    }
}
