package wynnvoice.mod.svc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import wynnvoice.protocol.Peer;
import wynnvoice.protocol.Relation;

class IconPickerTest {
    private static Peer peer(Relation relation, boolean reachable, boolean disabled) {
        return new Peer(UUID.randomUUID(), "p", disabled, relation, reachable, false);
    }

    @Test
    void reachableAndEnabledPeersAreTintedByRelation() {
        assertEquals(IconPicker.PARTY, IconPicker.iconFor(peer(Relation.PARTY, true, false)));
        assertEquals(IconPicker.FRIEND, IconPicker.iconFor(peer(Relation.FRIEND, true, false)));
        assertEquals(IconPicker.GUILD, IconPicker.iconFor(peer(Relation.GUILD, true, false)));
        assertEquals(IconPicker.STRANGER, IconPicker.iconFor(peer(Relation.NONE, true, false)));
    }

    @Test
    void unreachableOrDisabledPeersAreCrossedOutWhateverTheRelation() {
        for (Relation relation : Relation.values()) {
            assertEquals(IconPicker.UNREACHABLE, IconPicker.iconFor(peer(relation, false, false)));
            assertEquals(IconPicker.UNREACHABLE, IconPicker.iconFor(peer(relation, true, true)));
            assertEquals(IconPicker.UNREACHABLE, IconPicker.iconFor(peer(relation, false, true)));
        }
    }

    @Test
    void iconsLiveUnderTheModsTextures() {
        assertEquals("wynnvoice:textures/icons/party.png", IconPicker.PARTY.toString());
    }
}
