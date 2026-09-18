package wynnvoice.mod.wynn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Fixtures are lines captured from Wynncraft on 2026-09-18. */
class WynnChatTest {
    /** Prefix on most first lines. */
    static final String P1 = "\uDAFF\uDFFC\uDAFF\uDFFF\uDAFF\uDFFE ";
    /** Prefix on continuation lines and on some notifications. */
    static final String P2 = "\uDAFF\uDFFC\uDB00\uDC06 ";

    @Test
    void stripsEitherPrefixAndColourCodes() {
        assertEquals("You have successfully created a party.", WynnChat.body(P1 + "You have successfully created a party."));
        assertEquals("Party members: Scyu_", WynnChat.body(P2 + "Party members: Scyu_"));
        assertEquals("Syaoran3 has joined your party, say hello!", WynnChat.body(P2 + "§eSyaoran3 has joined your party, say hello!"));
    }

    @Test
    void joinsSoftWrappedLines() {
        assertEquals("The Aeon Origin World Event starts in 1m 58s! (552 blocks away) Click to track",
                WynnChat.body(P1 + "The Aeon Origin World Event starts in 1m 58s! (552 blocks\n" + P2 + "away) Click to track"));
        assertEquals("Found a bug? Use /bug to report it! For more information, visit wynncraft.com",
                WynnChat.body(P1 + "Found a bug? Use /bug to report it! For more information,\n" + P2 + "visit wynncraft.com"));
    }

    @Test
    void ignoresLinesWithoutAPrefix() {
        assertNull(WynnChat.body("\n      §eYou have been invited to join Syaoran3's party!\n"));
        assertNull(WynnChat.body("                      Click here to join\n"));
        assertNull(WynnChat.body("§7[§r§8Syaoran3§r§7] §rhello"));
    }
}
