package wynnvoice.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VoiceConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOG = LoggerFactory.getLogger("wynnvoice");

    public String relayHost = "localhost";
    public int relayPort = 9100;

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
        config.save(file);
        return config;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this));
    }
}
