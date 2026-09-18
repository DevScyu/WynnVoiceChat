package wynnvoice.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wynnvoice.mod.config.VoiceConfig.Notice;
import wynnvoice.protocol.VoiceTier;

class VoiceConfigTest {
    private final VoiceConfig config = new VoiceConfig();

    @Test
    void consentComesBeforeAnythingElse() {
        assertFalse(config.canConnect());
        assertEquals(Notice.CONSENT, config.pendingNotice(true));
        assertEquals(Notice.NONE, config.pendingNotice(false), "no Simple Voice Chat, nobody to ask");

        config.consentVersion = VoiceConfig.CONSENT_VERSION;
        assertTrue(config.canConnect());
        assertEquals(Notice.NONE, config.pendingNotice(true));

        config.enabled = false;
        assertFalse(config.canConnect());
        assertEquals(Notice.NONE, config.pendingNotice(true));
    }

    @Test
    void everyoneIsDowngradedUntilTheWarningIsAccepted() {
        config.consentVersion = VoiceConfig.CONSENT_VERSION;
        config.tier = VoiceTier.EVERYONE;
        assertEquals(VoiceTier.PARTY, config.effectiveTier());
        assertEquals(Notice.EVERYONE_WARNING, config.pendingNotice(true));

        config.everyoneWarningAccepted = true;
        assertEquals(VoiceTier.EVERYONE, config.effectiveTier());
        assertEquals(Notice.NONE, config.pendingNotice(true));

        config.tier = VoiceTier.FRIENDS_AND_GUILD;
        config.everyoneWarningAccepted = false;
        assertEquals(VoiceTier.FRIENDS_AND_GUILD, config.effectiveTier());
        assertEquals(Notice.NONE, config.pendingNotice(true));
    }

    @Test
    void guildChannelWaitsForItsWarningAfterTheEveryoneOne() {
        config.consentVersion = VoiceConfig.CONSENT_VERSION;
        assertFalse(config.effectiveGuildChannel());
        assertEquals(Notice.NONE, config.pendingNotice(true));

        config.guildChannel = true;
        assertFalse(config.effectiveGuildChannel());
        assertEquals(Notice.GUILD_WARNING, config.pendingNotice(true));
        config.tier = VoiceTier.EVERYONE;
        assertEquals(Notice.EVERYONE_WARNING, config.pendingNotice(true));

        config.everyoneWarningAccepted = true;
        config.guildWarningAccepted = true;
        assertTrue(config.effectiveGuildChannel());
        assertEquals(Notice.NONE, config.pendingNotice(true));
    }

    @Test
    void oldConsentVersionAsksAgain() {
        config.consentVersion = VoiceConfig.CONSENT_VERSION - 1;
        assertEquals(Notice.CONSENT, config.pendingNotice(true));
    }

    @Test
    void savesEveryFieldAndKeepsDefaultsForMissingOnes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("wynnvoice.json");
        Files.writeString(file, "{\"relayHost\":\"relay.example\",\"tier\":\"EVERYONE\"}");
        VoiceConfig loaded = VoiceConfig.load(file);
        assertTrue(loaded.enabled);
        assertEquals(0, loaded.consentVersion);
        assertEquals("relay.example", loaded.relayHost);
        assertTrue(loaded.blockAlsoIgnores);
        assertFalse(loaded.guildChannel);
        assertFalse(loaded.guildWarningAccepted);

        loaded.guildChannel = true;
        loaded.guildWarningAccepted = true;
        loaded.consentVersion = VoiceConfig.CONSENT_VERSION;
        loaded.everyoneWarningAccepted = true;
        loaded.enabled = false;
        loaded.blockAlsoIgnores = false;
        loaded.save();
        VoiceConfig reloaded = VoiceConfig.load(file);
        assertFalse(reloaded.enabled);
        assertEquals(VoiceTier.EVERYONE, reloaded.tier);
        assertEquals(VoiceConfig.CONSENT_VERSION, reloaded.consentVersion);
        assertTrue(reloaded.everyoneWarningAccepted);
        assertFalse(reloaded.blockAlsoIgnores);
        assertTrue(reloaded.guildChannel);
        assertTrue(reloaded.guildWarningAccepted);
        assertEquals(9100, reloaded.relayPort);
    }
}
