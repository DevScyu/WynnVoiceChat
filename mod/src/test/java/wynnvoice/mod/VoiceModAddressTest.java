package wynnvoice.mod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoiceModAddressTest {
    @Test
    void onlyWynncraftHostsActivate() {
        assertTrue(VoiceMod.isWynncraft("play.wynncraft.com"));
        assertTrue(VoiceMod.isWynncraft("Play.Wynncraft.com:25565"));
        assertTrue(VoiceMod.isWynncraft("wynncraft.com"));
        assertFalse(VoiceMod.isWynncraft("notwynncraft.com"));
        assertFalse(VoiceMod.isWynncraft("wynncraft.com.evil.example"));
        assertFalse(VoiceMod.isWynncraft("localhost"));
    }
}
