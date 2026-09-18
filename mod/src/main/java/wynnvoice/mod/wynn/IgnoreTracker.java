package wynnvoice.mod.wynn;

import java.util.Locale;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Hides Wynncraft's answer to the {@code /ignore} command we send after a confirmed block.
 * The exact response text is not captured anywhere, so any line naming the player and "ignore"
 * within a few seconds of the command counts; anything else stays visible.
 */
public final class IgnoreTracker {
    public static final long WINDOW_MS = 5_000;

    private final LongSupplier clock;
    private @Nullable String player;
    private long until;

    public IgnoreTracker(LongSupplier clock) {
        this.clock = clock;
    }

    public static boolean matches(String message, String player, @Nullable String realName) {
        String lower = message.toLowerCase(Locale.ROOT);
        boolean named = lower.contains(player.toLowerCase(Locale.ROOT)) || player.equalsIgnoreCase(realName);
        return named && lower.contains("ignor");
    }

    public void expect(String player) {
        this.player = player;
        until = clock.getAsLong() + WINDOW_MS;
    }

    /** @return whether the line is the response we are waiting for and should stay out of chat */
    public boolean onChat(String message, @Nullable String realName) {
        if (player == null) return false;
        if (clock.getAsLong() > until) {
            player = null;
            return false;
        }
        return matches(message, player, realName);
    }
}
