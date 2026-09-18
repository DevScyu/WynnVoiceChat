package wynnvoice.mod.wynn;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Hides Wynncraft's answer to the {@code /ignore} command sent after a confirmed block. */
public final class IgnoreTracker {
    private static final Pattern RESPONSE = Pattern.compile("(.+) has been (?:added to|removed from) your ignore list!");

    private @Nullable String player;

    /** The player named by the ignore-list notification, or null for any other line. */
    public static @Nullable String parse(String message) {
        String body = WynnChat.body(message);
        if (body == null) return null;
        Matcher m = RESPONSE.matcher(body);
        return m.matches() ? m.group(1) : null;
    }

    public void expect(String player) {
        this.player = player;
    }

    /** @return whether the line is the response we are waiting for and should stay out of chat */
    public boolean onChat(String message, @Nullable String realName) {
        if (player == null) return false;
        String named = parse(message);
        if (named == null || !(named.equalsIgnoreCase(player) || player.equalsIgnoreCase(realName))) return false;
        player = null;
        return true;
    }
}
