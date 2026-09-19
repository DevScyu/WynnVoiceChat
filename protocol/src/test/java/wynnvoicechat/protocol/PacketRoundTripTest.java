package wynnvoicechat.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import wynnvoicechat.protocol.Packet.*;

class PacketRoundTripTest {
    static Stream<Packet> packets() {
        byte[] serverId = new byte[Protocol.SERVER_ID_BYTES];
        byte[] secret = new byte[Protocol.SECRET_BYTES];
        for (int i = 0; i < serverId.length; i++) serverId[i] = (byte) (i * 7);
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) (255 - i);
        return Stream.of(
                new Hello(Protocol.VERSION, 20, "1.2.3"),
                new AuthChallenge(serverId),
                new Auth("Player_One", UUID.randomUUID()),
                new AuthResult(AuthStatus.BANNED, 3, "slurs"),
                new Join(VoiceTier.FRIENDS_AND_GUILD, "housing:Scyu_"),
                new Secret(secret, "voice.example.org", 24454, 32.0, 1000, VoiceTier.FRIENDS_AND_GUILD),
                new Update(VoiceTier.EVERYONE, "", true, true, false),
                new World("WC12"),
                new Position(-1234.5f, 64.25f, 8765.75f),
                new Social(SocialKind.FRIENDS, SocialAction.ADD, List.of("Alice", "Bób", "")),
                new Peers(List.of(
                        new Peer(UUID.randomUUID(), "Alice", false, Relation.PARTY, true, false),
                        new Peer(UUID.randomUUID(), "Bob", true, Relation.GUILD, false, true))),
                new Ended(EndReason.WORLD_MISMATCH, "You are not on that world"),
                new Block("Someone", false),
                new Report("Someone", "slurs in voice"),
                new Result(ResultKind.REPORT_OUTCOME, true, "Report #12 actioned"),
                new BlockList(),
                new BlockListResult(List.of("Alice", "Bob")),
                new GuildMute("Someone", true, 24),
                new Call("Someone", CallAction.INVITE),
                new CallState("Someone", UUID.randomUUID(), CallStateKind.EXPIRED),
                new Guild("ABC"));
    }

    @ParameterizedTest
    @MethodSource("packets")
    void roundTripsThroughPipeline(Packet packet) {
        EmbeddedChannel channel = new EmbeddedChannel();
        VoicePipeline.install(channel.pipeline());

        assertTrue(channel.writeOutbound(packet));
        for (ByteBuf chunk; (chunk = channel.readOutbound()) != null; ) channel.writeInbound(chunk);

        Packet decoded = channel.readInbound();
        assertEquals(packet, decoded);
        assertFalse(channel.finish());
    }

    @Test
    void samplesCoverEveryPacketType() {
        Set<Class<?>> sampled = packets().map(Packet::getClass).collect(Collectors.toSet());
        Set<Class<?>> declared = Set.of(Packet.class.getPermittedSubclasses());
        assertEquals(declared, sampled);
        assertEquals(21, declared.size());
    }

    @Test
    void protocolVersionIsTwo() {
        assertEquals(2, Protocol.VERSION, "terms version in AuthResult broke wire compatibility");
    }

    @Test
    void emptyLists() {
        Social social = new Social(SocialKind.PARTY, SocialAction.SET, List.of());
        Peers peers = new Peers(List.of());
        BlockListResult blocks = new BlockListResult(List.of());
        assertEquals(social, roundTrip(social));
        assertEquals(peers, roundTrip(peers));
        assertEquals(blocks, roundTrip(blocks));
    }

    @Test
    void rejectsUnknownPacketId() {
        ByteBuf buf = Unpooled.buffer().writeByte(200);
        assertThrows(DecoderException.class, () -> PacketCodec.decode(buf));
    }

    @Test
    void rejectsBadEnumOrdinal() {
        ByteBuf buf = Unpooled.buffer().writeByte(3).writeByte(99);
        assertThrows(DecoderException.class, () -> PacketCodec.decode(buf));
    }

    @Test
    void rejectsTruncatedPacket() {
        ByteBuf buf = Unpooled.buffer().writeByte(1).writeBytes(new byte[5]);
        assertThrows(DecoderException.class, () -> PacketCodec.decode(buf));
    }

    @Test
    void rejectsWrongSizedServerIdAndSecret() {
        assertThrows(IllegalArgumentException.class, () -> new AuthChallenge(new byte[19]));
        assertThrows(IllegalArgumentException.class, () -> new Secret(new byte[15], "h", 1, 1.0, 1, VoiceTier.PARTY));
    }

    @Test
    void varIntCoversFullRange() {
        for (int value : new int[] {0, 1, 127, 128, 255, 16384, Integer.MAX_VALUE, -1, Integer.MIN_VALUE}) {
            ByteBuf buf = Unpooled.buffer();
            PacketCodec.writeVarInt(buf, value);
            assertEquals(value, PacketCodec.readVarInt(buf));
            assertFalse(buf.isReadable());
        }
    }

    private static Packet roundTrip(Packet packet) {
        ByteBuf buf = Unpooled.buffer();
        PacketCodec.encode(packet, buf);
        Packet decoded = PacketCodec.decode(buf);
        assertFalse(buf.isReadable(), "trailing bytes: " + Arrays.toString(new byte[buf.readableBytes()]));
        return decoded;
    }
}
