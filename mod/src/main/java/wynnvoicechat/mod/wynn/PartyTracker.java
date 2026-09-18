package wynnvoicechat.mod.wynn;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.jspecify.annotations.Nullable;
import wynnvoicechat.protocol.SocialAction;

/**
 * Keeps the Wynncraft party member list from chat: a {@code /party list} response on world join,
 * then the join/leave/kick/disband messages. Patterns follow Wynntils' PartyModel.
 */
public final class PartyTracker {
    public sealed interface Event {
        record Listed(List<String> members) implements Event {}
        record NotInParty() implements Event {}
        record Created() implements Event {}
        record Joined(String name) implements Event {}
        record Left(String name) implements Event {}
        record SelfLeft() implements Event {}
        record Restored() implements Event {}
    }

    private static final Pattern LIST = Pattern.compile("Party members: (.*)");
    private static final Pattern LIST_SEPARATOR = Pattern.compile(",(?: and)? ");
    private static final Pattern NOT_IN_PARTY = Pattern.compile("You must be in a party to use this\\.");
    private static final Pattern CREATED = Pattern.compile("You have successfully created a party\\.");
    private static final Pattern JOINED = Pattern.compile("(.+) has joined your party, say hello!");
    private static final Pattern LEFT = Pattern.compile("(.+) has (?:left|been kicked from) the party!");
    private static final Pattern SELF_LEFT = Pattern.compile(
            "You have left your current party|You have been kicked from your party|Your party has been disbanded");
    private static final Pattern RESTORED = Pattern.compile("Your previous party was restored");
    private static final Pattern NICKNAME = Pattern.compile(".+?'s? real (?:user)?name is (.+)");

    /** The username behind a nicknamed player, from the hover text Wynncraft attaches to the nick. */
    public static @Nullable String realName(Component message) {
        return message.visit((style, text) -> {
            if (style.getHoverEvent() instanceof HoverEvent.ShowText hover) {
                Matcher m = NICKNAME.matcher(hover.value().getString());
                if (m.find()) return Optional.of(m.group(1));
            }
            return Optional.<String>empty();
        }, Style.EMPTY).orElse(null);
    }

    /** Null unless the line is a party notification. */
    public static @Nullable Event parse(String message) {
        String body = WynnChat.body(message);
        if (body == null) return null;
        Matcher m;
        if ((m = LIST.matcher(body)).matches()) return new Event.Listed(List.of(LIST_SEPARATOR.split(m.group(1))));
        if (NOT_IN_PARTY.matcher(body).matches()) return new Event.NotInParty();
        if (CREATED.matcher(body).matches()) return new Event.Created();
        if ((m = JOINED.matcher(body)).matches()) return new Event.Joined(m.group(1));
        if ((m = LEFT.matcher(body)).matches()) return new Event.Left(m.group(1));
        if (SELF_LEFT.matcher(body).matches()) return new Event.SelfLeft();
        if (RESTORED.matcher(body).matches()) return new Event.Restored();
        return null;
    }

    private final String self;
    private final Runnable sendListCommand;
    private final BiConsumer<SocialAction, List<String>> onChange;
    private final Set<String> members = new LinkedHashSet<>();
    private boolean expectingList;

    public PartyTracker(String self, Runnable sendListCommand, BiConsumer<SocialAction, List<String>> onChange) {
        this.self = self;
        this.sendListCommand = sendListCommand;
        this.onChange = onChange;
    }

    public Set<String> members() {
        return Set.copyOf(members);
    }

    public void requestList() {
        expectingList = true;
        sendListCommand.run();
    }

    public void reset() {
        members.clear();
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
                set(listed.members());
                return consumeExpected();
            }
            case Event.NotInParty ignored -> {
                set(List.of());
                return consumeExpected();
            }
            case Event.Created ignored -> set(List.of(self));
            case Event.Joined joined -> {
                String name = realName != null ? realName : joined.name();
                if (name.equals(self)) requestList();
                else change(SocialAction.ADD, name);
            }
            case Event.Left left -> change(SocialAction.REMOVE, realName != null ? realName : left.name());
            case Event.SelfLeft ignored -> set(List.of());
            case Event.Restored ignored -> requestList();
        }
        return false;
    }

    private boolean consumeExpected() {
        boolean expected = expectingList;
        expectingList = false;
        return expected;
    }

    private void set(List<String> names) {
        members.clear();
        members.addAll(names);
        onChange.accept(SocialAction.SET, List.copyOf(members));
    }

    private void change(SocialAction action, String name) {
        if (action == SocialAction.ADD) members.add(name);
        else members.remove(name);
        onChange.accept(action, List.of(name));
    }
}
