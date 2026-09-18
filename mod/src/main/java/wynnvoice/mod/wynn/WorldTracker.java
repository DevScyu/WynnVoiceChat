package wynnvoice.mod.wynn;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WorldTracker {
    public static final UUID TAB_LIST_ENTRY = UUID.fromString("16ff7452-714f-2752-b3cd-c3cb2068f6af");
    private static final Pattern WORLD = Pattern.compile("^ {2}Global \\[(.+)]$");
    private static final Pattern HOUSING = Pattern.compile("^ {2}([^§\"\\\\]{1,35})$");
    private static final String UNKNOWN_WORLD = "WC??";

    public record State(String world, String housing) {
        public static final State NONE = new State("", "");

        public boolean onWorld() {
            return !world.isEmpty();
        }

        public String instance() {
            return housing.isEmpty() ? "" : "housing:" + housing;
        }
    }

    private State state = State.NONE;

    public State state() {
        return state;
    }

    public boolean update(String tabListDisplayName) {
        State next = next(state, tabListDisplayName);
        boolean changed = !next.equals(state);
        state = next;
        return changed;
    }

    public static State next(State current, String tabListDisplayName) {
        if (tabListDisplayName == null) return State.NONE;
        String plain = tabListDisplayName.replaceAll("§.", "");
        Matcher world = WORLD.matcher(plain);
        if (world.matches()) return new State(world.group(1), "");
        Matcher housing = HOUSING.matcher(plain);
        if (housing.matches()) return new State(current.onWorld() ? current.world() : UNKNOWN_WORLD, housing.group(1));
        return current;
    }
}
