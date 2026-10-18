package wynnvoicechat.mod.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import wynnvoicechat.protocol.Peer;
import wynnvoicechat.protocol.Relation;

/** Renders the last {@code Peers} as chat lines: one per relation, in relation order, names only, never world or position. */
public final class VoiceRoster {
    private VoiceRoster() {}

    /** {@code label} maps {@code party|friend|guild|none|muted|unreachable} to display text. */
    public static Map<Relation, String> lines(List<Peer> peers, Function<String, String> label) {
        Map<Relation, List<Peer>> groups = peers.stream().collect(Collectors.groupingBy(Peer::relation));
        Map<Relation, String> lines = new EnumMap<>(Relation.class);
        for (Relation relation : Relation.values()) {
            List<Peer> group = groups.get(relation);
            if (group == null) continue;
            String names = group.stream()
                    .sorted(Comparator.comparing(Peer::name, String.CASE_INSENSITIVE_ORDER))
                    .map(peer -> describe(peer, label))
                    .collect(Collectors.joining(", "));
            lines.put(relation, label.apply(relation.name().toLowerCase(Locale.ROOT)) + ": " + names);
        }
        return lines;
    }

    private static String describe(Peer peer, Function<String, String> label) {
        List<String> marks = new ArrayList<>(2);
        if (peer.disabled()) marks.add(label.apply("muted"));
        if (!peer.reachable()) marks.add(label.apply("unreachable"));
        return marks.isEmpty() ? peer.name() : peer.name() + " (" + String.join(", ", marks) + ")";
    }
}
