package wynnvoice.mod.wynn;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wynnvoice.protocol.SocialAction;

/**
 * Keeps the Wynncraft friend list from chat: a {@code /friend list} response on world join, then
 * the added/removed messages. Patterns follow Wynntils' FriendsModel.
 */
public final class FriendsTracker {
    public sealed interface Event {
        record Listed(List<String> friends) implements Event {}
        /** First line of the empty-list response; the hint line follows. */
        record NoFriends() implements Event {}
        record NoFriendsHint() implements Event {}
        record Added(String name) implements Event {}
        record Removed(String name) implements Event {}
    }

    private static final Pattern LIST = Pattern.compile(".+'s? friends \\(.+\\): (.*)");
    private static final Pattern LIST_SEPARATOR = Pattern.compile(",(?: and)? ");
    private static final Pattern NO_FRIENDS = Pattern.compile("We couldn't find any friends\\.");
    private static final Pattern NO_FRIENDS_HINT = Pattern.compile("Try typing /friend add Username!");
    private static final Pattern ADDED = Pattern.compile("(.+) has been added to your friends!");
    private static final Pattern REMOVED = Pattern.compile("(.+) has been removed from your friends!");

    /** Null unless the line is a friend notification. */
    public static @Nullable Event parse(String message) {
        String body = WynnChat.body(message);
        if (body == null) return null;
        Matcher m;
        if ((m = LIST.matcher(body)).matches()) return new Event.Listed(List.of(LIST_SEPARATOR.split(m.group(1))));
        if (NO_FRIENDS.matcher(body).matches()) return new Event.NoFriends();
        if (NO_FRIENDS_HINT.matcher(body).matches()) return new Event.NoFriendsHint();
        if ((m = ADDED.matcher(body)).matches()) return new Event.Added(m.group(1));
        if ((m = REMOVED.matcher(body)).matches()) return new Event.Removed(m.group(1));
        return null;
    }

    private final Runnable sendListCommand;
    private final BiConsumer<SocialAction, List<String>> onChange;
    private final Set<String> friends = new LinkedHashSet<>();
    private boolean expectingList;

    public FriendsTracker(Runnable sendListCommand, BiConsumer<SocialAction, List<String>> onChange) {
        this.sendListCommand = sendListCommand;
        this.onChange = onChange;
    }

    public Set<String> friends() {
        return Set.copyOf(friends);
    }

    public void requestList() {
        expectingList = true;
        sendListCommand.run();
    }

    public void reset() {
        friends.clear();
        expectingList = false;
    }

    /**
     * @param realName the username behind a nickname, when the message carries one
     * @return whether the message was the response we asked for and should stay out of chat
     */
    public boolean onChat(String message, @Nullable String realName) {
        Event event = parse(message);
        switch (event) {
            case null -> {
                return false;
            }
            case Event.Listed listed -> {
                set(listed.friends());
                return consumeExpected();
            }
            case Event.NoFriends ignored -> {
                return expectingList;
            }
            case Event.NoFriendsHint ignored -> {
                set(List.of());
                return consumeExpected();
            }
            case Event.Added added -> change(SocialAction.ADD, realName != null ? realName : added.name());
            case Event.Removed removed -> change(SocialAction.REMOVE, realName != null ? realName : removed.name());
        }
        return false;
    }

    private boolean consumeExpected() {
        boolean expected = expectingList;
        expectingList = false;
        return expected;
    }

    private void set(List<String> names) {
        friends.clear();
        friends.addAll(names);
        onChange.accept(SocialAction.SET, List.copyOf(friends));
    }

    private void change(SocialAction action, String name) {
        if (action == SocialAction.ADD) friends.add(name);
        else friends.remove(name);
        onChange.accept(action, List.of(name));
    }
}
