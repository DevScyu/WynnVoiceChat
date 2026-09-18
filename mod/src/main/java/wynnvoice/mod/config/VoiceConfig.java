package wynnvoice.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wynnvoice.protocol.VoiceTier;

/** Persisted user choices plus the pure decisions derived from them; the screens and commands only mutate and save. */
public final class VoiceConfig {
    /** Bump when the consent notice changes materially; users must accept again. */
    public static final int CONSENT_VERSION = 1;

    public enum Notice { NONE, CONSENT, EVERYONE_WARNING, GUILD_WARNING }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOG = LoggerFactory.getLogger("wynnvoice");

    public String relayHost = "localhost";
    public int relayPort = 9100;
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

    public boolean hasConsent() {
        return consentVersion >= CONSENT_VERSION;
    }

    public boolean canConnect() {
        return enabled && hasConsent();
    }

    private boolean needsEveryoneWarning() {
        return tier == VoiceTier.EVERYONE && !everyoneWarningAccepted;
    }

    /** EVERYONE is only honoured once the warning was accepted; the relay clamps to its own cap on top. */
    public VoiceTier effectiveTier() {
        return needsEveryoneWarning() ? VoiceTier.PARTY : tier;
    }

    private boolean needsGuildWarning() {
        return guildChannel && !guildWarningAccepted;
    }

    /** The guild channel is only honoured once its warning was accepted. */
    public boolean effectiveGuildChannel() {
        return guildChannel && guildWarningAccepted;
    }

    /** The consent notice is only worth showing to people who can actually use voice. */
    public Notice pendingNotice(boolean svcInstalled) {
        if (!enabled) return Notice.NONE;
        if (!hasConsent()) return svcInstalled ? Notice.CONSENT : Notice.NONE;
        if (needsEveryoneWarning()) return Notice.EVERYONE_WARNING;
        return needsGuildWarning() ? Notice.GUILD_WARNING : Notice.NONE;
    }
}
