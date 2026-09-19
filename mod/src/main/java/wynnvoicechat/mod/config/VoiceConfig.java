package wynnvoicechat.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wynnvoicechat.protocol.VoiceTier;

/** Persisted user choices plus the pure decisions derived from them; the screens and commands only mutate and save. */
public final class VoiceConfig {
    /** No relay has announced a terms version yet: any acceptance counts. */
    public static final int TERMS_UNKNOWN = 0;

    public enum Notice { NONE, CONSENT, EVERYONE_WARNING, GUILD_WARNING }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOG = LoggerFactory.getLogger("wynnvoicechat");

    public boolean enabled = true;
    public VoiceTier tier = VoiceTier.PARTY;
    public int consentVersion;
    public boolean everyoneWarningAccepted;
    /** A confirmed block or unblock also sends {@code /ignore add|remove <player>}. */
    public boolean blockAlsoIgnores = true;
    /** Hear and be heard by guild members anywhere, unless in a party. */
    public boolean guildChannel;
    public boolean guildWarningAccepted;
    /** Refuse incoming friend calls. */
    public boolean dnd;
    /** Ring, call and presence cues. */
    public boolean sounds = true;
    /** The on-screen call panel and pill; the keys work regardless. */
    public boolean hud = true;
    private transient Path file;

    public static VoiceConfig load(Path file) throws IOException {
        VoiceConfig config = null;
        if (Files.exists(file)) {
            try {
                config = GSON.fromJson(Files.readString(file), VoiceConfig.class);
            } catch (JsonParseException e) {
                LOG.warn("Ignoring malformed {}: {}", file, e.getMessage());
            }
        }
        if (config == null) config = new VoiceConfig();
        if (config.tier == null) config.tier = VoiceTier.PARTY;
        config.file = file;
        config.save();
        return config;
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this));
    }

    /** Consent is the terms version last accepted; the relay, not the jar, says which version is current. */
    public boolean hasConsent(int relayTermsVersion) {
        return consentVersion > 0 && consentVersion >= relayTermsVersion;
    }

    public boolean canConnect(int relayTermsVersion) {
        return enabled && hasConsent(relayTermsVersion);
    }

    /** The rules screen is pointless while the relay caps the audience below EVERYONE. */
    private boolean needsEveryoneWarning(VoiceTier relayMaxTier) {
        return tier == VoiceTier.EVERYONE && !everyoneWarningAccepted && relayMaxTier == VoiceTier.EVERYONE;
    }

    /** EVERYONE is only honoured once the warning was accepted; the relay clamps to its own cap on top. */
    public VoiceTier effectiveTier(VoiceTier relayMaxTier) {
        return needsEveryoneWarning(relayMaxTier) ? VoiceTier.PARTY : tier;
    }

    private boolean needsGuildWarning() {
        return guildChannel && !guildWarningAccepted;
    }

    /** The guild channel is only honoured once its warning was accepted. */
    public boolean effectiveGuildChannel() {
        return guildChannel && guildWarningAccepted;
    }

    /** The consent notice is only worth showing to people who can actually use voice. */
    public Notice pendingNotice(boolean svcInstalled, VoiceTier relayMaxTier, int relayTermsVersion) {
        if (!enabled) return Notice.NONE;
        if (!hasConsent(relayTermsVersion)) return svcInstalled ? Notice.CONSENT : Notice.NONE;
        if (needsEveryoneWarning(relayMaxTier)) return Notice.EVERYONE_WARNING;
        return needsGuildWarning() ? Notice.GUILD_WARNING : Notice.NONE;
    }
}
