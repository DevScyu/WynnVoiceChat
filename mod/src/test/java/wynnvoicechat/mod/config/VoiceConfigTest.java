package wynnvoicechat.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wynnvoicechat.mod.config.VoiceConfig.Notice;
import wynnvoicechat.protocol.VoiceTier;

class VoiceConfigTest {
    private final VoiceConfig config = new VoiceConfig();

    @Test
    void consentComesBeforeAnythingElse() {
        assertFalse(config.canConnect(VoiceConfig.TERMS_UNKNOWN));
        assertEquals(Notice.CONSENT, config.pendingNotice(true, VoiceTier.EVERYONE, VoiceConfig.TERMS_UNKNOWN));
        assertEquals(Notice.NONE, config.pendingNotice(false, VoiceTier.EVERYONE, VoiceConfig.TERMS_UNKNOWN), "no Simple Voice Chat, nobody to ask");

        config.consentVersion = 1;
        assertTrue(config.canConnect(VoiceConfig.TERMS_UNKNOWN));
        assertEquals(Notice.NONE, config.pendingNotice(true, VoiceTier.EVERYONE, VoiceConfig.TERMS_UNKNOWN));

        config.enabled = false;
        assertFalse(config.canConnect(VoiceConfig.TERMS_UNKNOWN));
        assertEquals(Notice.NONE, config.pendingNotice(true, VoiceTier.EVERYONE, VoiceConfig.TERMS_UNKNOWN));
    }

    @Test
    void everyoneIsDowngradedUntilTheWarningIsAccepted() {
        config.consentVersion = 1;
        config.tier = VoiceTier.EVERYONE;
        assertEquals(VoiceTier.PARTY, config.effectiveTier(VoiceTier.EVERYONE));
        assertEquals(Notice.EVERYONE_WARNING, config.pendingNotice(true, VoiceTier.EVERYONE, VoiceConfig.TERMS_UNKNOWN));

        config.everyoneWarningAccepted = true;
        assertEquals(VoiceTier.EVERYONE, config.effectiveTier(VoiceTier.EVERYONE));
        assertEquals(Notice.NONE, config.pendingNotice(true, VoiceTier.EVERYONE, VoiceConfig.TERMS_UNKNOWN));

        config.tier = VoiceTier.FRIENDS_AND_GUILD;
        config.everyoneWarningAccepted = false;
        assertEquals(VoiceTier.FRIENDS_AND_GUILD, config.effectiveTier(VoiceTier.EVERYONE));
        assertEquals(Notice.NONE, config.pendingNotice(true, VoiceTier.EVERYONE, VoiceConfig.TERMS_UNKNOWN));
    }

    @Test
    void guildChannelWaitsForItsWarningAfterTheEveryoneOne() {
        config.consentVersion = 1;
        assertFalse(config.effectiveGuildChannel());
        assertEquals(Notice.NONE, config.pendingNotice(true, VoiceTier.EVERYONE, VoiceConfig.TERMS_UNKNOWN));

        config.guildChannel = true;
        assertFalse(config.effectiveGuildChannel());
        assertEquals(Notice.GUILD_WARNING, config.pendingNotice(true, VoiceTier.EVERYONE, VoiceConfig.TERMS_UNKNOWN));
        config.tier = VoiceTier.EVERYONE;
        assertEquals(Notice.EVERYONE_WARNING, config.pendingNotice(true, VoiceTier.EVERYONE, VoiceConfig.TERMS_UNKNOWN));

        config.everyoneWarningAccepted = true;
        config.guildWarningAccepted = true;
        assertTrue(config.effectiveGuildChannel());
        assertEquals(Notice.NONE, config.pendingNotice(true, VoiceTier.EVERYONE, VoiceConfig.TERMS_UNKNOWN));
    }

    @Test
    void everyoneWarningWaitsWhileTheRelayCapsBelowEveryone() {
        config.consentVersion = 1;
        config.tier = VoiceTier.EVERYONE;
        assertEquals(Notice.NONE, config.pendingNotice(true, VoiceTier.FRIENDS_AND_GUILD, VoiceConfig.TERMS_UNKNOWN));
        assertEquals(VoiceTier.EVERYONE, config.effectiveTier(VoiceTier.FRIENDS_AND_GUILD), "no warning to wait for; the relay clamps");
        assertEquals(Notice.EVERYONE_WARNING, config.pendingNotice(true, VoiceTier.EVERYONE, VoiceConfig.TERMS_UNKNOWN));
        assertEquals(VoiceTier.PARTY, config.effectiveTier(VoiceTier.EVERYONE));

        config.guildChannel = true;
        assertEquals(Notice.GUILD_WARNING, config.pendingNotice(true, VoiceTier.PARTY, VoiceConfig.TERMS_UNKNOWN), "the guild warning is not held back by the cap");
    }

    @Test
    void consentFollowsTheRelaysTermsVersion() {
        assertFalse(config.hasConsent(VoiceConfig.TERMS_UNKNOWN), "fresh install, no relay contact yet");

        config.consentVersion = 1;
        assertTrue(config.hasConsent(VoiceConfig.TERMS_UNKNOWN), "accepted once, relay not heard from yet");
        assertTrue(config.hasConsent(1), "equal");
        assertFalse(config.hasConsent(2), "behind: the terms changed");
        assertEquals(Notice.CONSENT, config.pendingNotice(true, VoiceTier.EVERYONE, 2));
        assertFalse(config.canConnect(2));

        config.consentVersion = 3;
        assertTrue(config.hasConsent(2), "ahead: accepted on a relay that has since rolled back");
    }

    @Test
    void savesEveryFieldAndKeepsDefaultsForMissingOnes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("wynnvoicechat.json");
        Files.writeString(file, "{\"relayHost\":\"relay.example\",\"tier\":\"EVERYONE\"}");
        VoiceConfig loaded = VoiceConfig.load(file);
        assertEquals("relay.wynnvoicechat.com", new VoiceConfig().relayHost);
        assertTrue(loaded.enabled);
        assertEquals(0, loaded.consentVersion);
        assertEquals("relay.example", loaded.relayHost);
        assertTrue(loaded.blockAlsoIgnores);
        assertFalse(loaded.guildChannel);
        assertFalse(loaded.guildWarningAccepted);

        loaded.guildChannel = true;
        loaded.guildWarningAccepted = true;
        loaded.consentVersion = 1;
        loaded.everyoneWarningAccepted = true;
        loaded.enabled = false;
        loaded.blockAlsoIgnores = false;
        loaded.save();
        VoiceConfig reloaded = VoiceConfig.load(file);
        assertFalse(reloaded.enabled);
        assertEquals(VoiceTier.EVERYONE, reloaded.tier);
        assertEquals(1, reloaded.consentVersion);
        assertTrue(reloaded.everyoneWarningAccepted);
        assertFalse(reloaded.blockAlsoIgnores);
        assertTrue(reloaded.guildChannel);
        assertTrue(reloaded.guildWarningAccepted);
        assertEquals(9100, reloaded.relayPort);
    }
}
