package wynnvoicechat.mod.svc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import wynnvoicechat.protocol.Peer;
import wynnvoicechat.protocol.Relation;

class IconPickerTest {
    private static Peer peer(Relation relation, boolean reachable, boolean disabled) {
        return new Peer(UUID.randomUUID(), "p", disabled, relation, reachable, false);
    }

    @Test
    void reachableAndEnabledPeersGetTheSpeakerTintedByRelation() {
        assertEquals(new IconPicker.Icon(IconPicker.SPEAKER, 0xFF55FF55), IconPicker.iconFor(peer(Relation.PARTY, true, false)));
        assertEquals(new IconPicker.Icon(IconPicker.SPEAKER, 0xFF55AAFF), IconPicker.iconFor(peer(Relation.FRIEND, true, false)));
        assertEquals(new IconPicker.Icon(IconPicker.SPEAKER, 0xFFFFC828), IconPicker.iconFor(peer(Relation.GUILD, true, false)));
        assertEquals(new IconPicker.Icon(IconPicker.SPEAKER, 0xFFFFFFFF), IconPicker.iconFor(peer(Relation.NONE, true, false)));
    }

    @Test
    void unreachableOrDisabledPeersGetTheRedCrossedSpeakerWhateverTheRelation() {
        IconPicker.Icon crossed = new IconPicker.Icon(IconPicker.SPEAKER_OFF, 0xFFFF5555);
        for (Relation relation : Relation.values()) {
            assertEquals(crossed, IconPicker.iconFor(peer(relation, false, false)));
            assertEquals(crossed, IconPicker.iconFor(peer(relation, true, true)));
            assertEquals(crossed, IconPicker.iconFor(peer(relation, false, true)));
        }
    }

    @Test
    void idlePeersGetTheQuietSpeakerInTheirRelationTintAtSixtyPercent() {
        assertEquals(new IconPicker.Icon(IconPicker.SPEAKER_QUIET, 0xFF339933), IconPicker.idleFor(peer(Relation.PARTY, true, false)));
        assertEquals(new IconPicker.Icon(IconPicker.SPEAKER_QUIET, 0xFF336699), IconPicker.idleFor(peer(Relation.FRIEND, true, false)));
        assertEquals(new IconPicker.Icon(IconPicker.SPEAKER_QUIET, 0xFF997818), IconPicker.idleFor(peer(Relation.GUILD, true, false)));
        assertEquals(new IconPicker.Icon(IconPicker.SPEAKER_QUIET, 0xFF999999), IconPicker.idleFor(peer(Relation.NONE, true, false)));
    }

    @Test
    void idleIsOnlyForPeersWeCouldHear() {
        assertNull(IconPicker.idleFor(peer(Relation.PARTY, false, false)));
        assertNull(IconPicker.idleFor(peer(Relation.PARTY, true, true)));
    }

    @Test
    void iconsLiveUnderTheModsTextures() {
        assertEquals("wynnvoicechat:textures/icons/speaker_off.png", IconPicker.SPEAKER_OFF.toString());
    }
}
