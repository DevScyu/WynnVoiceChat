package wynnvoice.mod.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import wynnvoice.protocol.Peer;
import wynnvoice.protocol.Relation;

class VoiceRosterTest {
    private static Peer peer(String name, Relation relation, boolean disabled, boolean reachable) {
        return new Peer(UUID.randomUUID(), name, disabled, relation, reachable, false);
    }

    @Test
    void groupsByRelationInOrderSortsNamesAndMarksMutedAndUnreachable() {
        List<String> lines = VoiceRoster.lines(List.of(
                peer("zed", Relation.NONE, true, false),
                peer("Bob", Relation.PARTY, true, true),
                peer("Carl", Relation.GUILD, false, false),
                peer("alice", Relation.PARTY, false, true),
                peer("Eve", Relation.NONE, false, true)), key -> key.toUpperCase());

        assertEquals(List.of(
                "PARTY: alice, Bob (MUTED)",
                "GUILD: Carl (UNREACHABLE)",
                "NONE: Eve, zed (MUTED, UNREACHABLE)"), lines);
    }

    @Test
    void noPeersNoLines() {
        assertEquals(List.of(), VoiceRoster.lines(List.of(), key -> key));
    }
}
