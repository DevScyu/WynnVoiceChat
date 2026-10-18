package wynnvoicechat.mod.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import wynnvoicechat.protocol.Peer;
import wynnvoicechat.protocol.Relation;

class VoiceRosterTest {
    private static Peer peer(String name, Relation relation, boolean disabled, boolean reachable) {
        return new Peer(UUID.randomUUID(), name, disabled, relation, reachable, false);
    }

    @Test
    void groupsByRelationInOrderSortsNamesAndMarksMutedAndUnreachable() {
        Map<Relation, String> lines = VoiceRoster.lines(List.of(
                peer("zed", Relation.NONE, true, false),
                peer("Bob", Relation.PARTY, true, true),
                peer("Carl", Relation.GUILD, false, false),
                peer("alice", Relation.PARTY, false, true),
                peer("Eve", Relation.NONE, false, true)), key -> key.toUpperCase());

        assertEquals(List.of(Relation.PARTY, Relation.GUILD, Relation.NONE), List.copyOf(lines.keySet()));
        assertEquals(List.of(
                "PARTY: alice, Bob (MUTED)",
                "GUILD: Carl (UNREACHABLE)",
                "NONE: Eve, zed (MUTED, UNREACHABLE)"), List.copyOf(lines.values()));
    }

    @Test
    void noPeersNoLines() {
        assertEquals(Map.of(), VoiceRoster.lines(List.of(), key -> key));
    }
}
