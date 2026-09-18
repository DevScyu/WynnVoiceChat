package wynnvoicechat.mod.wynn;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Wynncraft prefixes every server notification with glyphs from its {@code chat/prefix} font and
 * soft-wraps long lines as {@code "\n" + glyphs + " "}; captured 2026-09-18. Player chat has no prefix.
 */
final class WynnChat {
    private static final String GLYPHS = "[\\x{CFC00}-\\x{D03FF}]+";
    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern SOFT_WRAP = Pattern.compile("\\s*\\n" + GLYPHS + " ");
    private static final Pattern PREFIX = Pattern.compile("^" + GLYPHS + " ");

    private WynnChat() {}

    /** The notification text without prefix, wraps or colour codes; null when the line is not a server notification. */
    static @Nullable String body(String message) {
        String plain = SOFT_WRAP.matcher(FORMATTING.matcher(message).replaceAll("")).replaceAll(" ");
        Matcher prefix = PREFIX.matcher(plain);
        return prefix.find() ? plain.substring(prefix.end()) : null;
    }
}
