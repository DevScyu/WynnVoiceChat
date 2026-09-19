package wynnvoicechat.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import wynnvoicechat.protocol.Packet.*;

public final class PacketCodec {
    private static final int MAX_STRING_BYTES = 4096;
    private static final int MAX_LIST_SIZE = 4096;

    private PacketCodec() {}

    public static void encode(Packet packet, ByteBuf out) {
        switch (packet) {
            case Hello p -> {
                out.writeByte(0);
                writeVarInt(out, p.protocolVersion());
                writeVarInt(out, p.svcCompatVersion());
                writeString(out, p.modVersion());
            }
            case AuthChallenge p -> {
                out.writeByte(1);
                out.writeBytes(p.serverId());
            }
            case Auth p -> {
                out.writeByte(2);
                writeString(out, p.username());
                writeUuid(out, p.uuid());
            }
            case AuthResult p -> {
                out.writeByte(3);
                writeEnum(out, p.status());
            }
            case Join p -> {
                out.writeByte(4);
                writeEnum(out, p.tier());
                writeString(out, p.instance());
            }
            case Secret p -> {
                out.writeByte(5);
                out.writeBytes(p.secret());
                writeString(out, p.host());
                writeVarInt(out, p.port());
                out.writeDouble(p.range());
                writeVarInt(out, p.keepAliveMs());
                writeEnum(out, p.maxTier());
            }
            case Update p -> {
                out.writeByte(6);
                writeEnum(out, p.tier());
                writeString(out, p.instance());
                out.writeBoolean(p.svcDisabled());
                out.writeBoolean(p.guildChannel());
                out.writeBoolean(p.dnd());
            }
            case World p -> {
                out.writeByte(7);
                writeString(out, p.world());
            }
            case Position p -> {
                out.writeByte(8);
                out.writeFloat(p.x());
                out.writeFloat(p.y());
                out.writeFloat(p.z());
            }
            case Social p -> {
                out.writeByte(9);
                writeEnum(out, p.kind());
                writeEnum(out, p.action());
                writeVarInt(out, p.names().size());
                p.names().forEach(name -> writeString(out, name));
            }
            case Peers p -> {
                out.writeByte(10);
                writeVarInt(out, p.peers().size());
                for (Peer peer : p.peers()) {
                    writeUuid(out, peer.uuid());
                    writeString(out, peer.name());
                    out.writeBoolean(peer.disabled());
                    writeEnum(out, peer.relation());
                    out.writeBoolean(peer.reachable());
                    out.writeBoolean(peer.guildChannel());
                }
            }
            case Ended p -> {
                out.writeByte(11);
                writeEnum(out, p.reason());
                writeString(out, p.message());
            }
            case Block p -> {
                out.writeByte(12);
                writeString(out, p.targetName());
                out.writeBoolean(p.blocked());
            }
            case Report p -> {
                out.writeByte(13);
                writeString(out, p.targetName());
                writeString(out, p.reason());
            }
            case Result p -> {
                out.writeByte(14);
                writeEnum(out, p.kind());
                out.writeBoolean(p.ok());
                writeString(out, p.message());
            }
            case BlockList p -> out.writeByte(15);
            case BlockListResult p -> {
                out.writeByte(16);
                writeVarInt(out, p.names().size());
                p.names().forEach(name -> writeString(out, name));
            }
            case GuildMute p -> {
                out.writeByte(17);
                writeString(out, p.targetName());
                out.writeBoolean(p.muted());
                writeVarInt(out, p.hours());
            }
            case Call p -> {
                out.writeByte(18);
                writeString(out, p.targetName());
                writeEnum(out, p.action());
            }
            case CallState p -> {
                out.writeByte(19);
                writeString(out, p.peerName());
                writeUuid(out, p.peerUuid());
                writeEnum(out, p.state());
            }
            case Guild p -> {
                out.writeByte(20);
                writeString(out, p.prefix());
            }
        }
    }

    public static Packet decode(ByteBuf in) {
        int id = in.readUnsignedByte();
        return switch (id) {
            case 0 -> new Hello(readVarInt(in), readVarInt(in), readString(in));
            case 1 -> new AuthChallenge(readBytes(in, Protocol.SERVER_ID_BYTES));
            case 2 -> new Auth(readString(in), readUuid(in));
            case 3 -> new AuthResult(readEnum(in, AuthStatus.class));
            case 4 -> new Join(readEnum(in, VoiceTier.class), readString(in));
            case 5 -> new Secret(readBytes(in, Protocol.SECRET_BYTES), readString(in), readVarInt(in), in.readDouble(), readVarInt(in), readEnum(in, VoiceTier.class));
            case 6 -> new Update(readEnum(in, VoiceTier.class), readString(in), in.readBoolean(), in.readBoolean(), in.readBoolean());
            case 7 -> new World(readString(in));
            case 8 -> new Position(in.readFloat(), in.readFloat(), in.readFloat());
            case 9 -> new Social(readEnum(in, SocialKind.class), readEnum(in, SocialAction.class), readStrings(in));
            case 10 -> {
                int size = readListSize(in);
                List<Peer> peers = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    peers.add(new Peer(readUuid(in), readString(in), in.readBoolean(), readEnum(in, Relation.class), in.readBoolean(), in.readBoolean()));
                }
                yield new Peers(peers);
            }
            case 11 -> new Ended(readEnum(in, EndReason.class), readString(in));
            case 12 -> new Block(readString(in), in.readBoolean());
            case 13 -> new Report(readString(in), readString(in));
            case 14 -> new Result(readEnum(in, ResultKind.class), in.readBoolean(), readString(in));
            case 15 -> new BlockList();
            case 16 -> new BlockListResult(readStrings(in));
            case 17 -> new GuildMute(readString(in), in.readBoolean(), readVarInt(in));
            case 18 -> new Call(readString(in), readEnum(in, CallAction.class));
            case 19 -> new CallState(readString(in), readUuid(in), readEnum(in, CallStateKind.class));
            case 20 -> new Guild(readString(in));
            default -> throw new DecoderException("Unknown packet id " + id);
        };
    }

    static void writeVarInt(ByteBuf out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarInt(ByteBuf in) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = in.readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new DecoderException("VarInt too long");
    }

    private static void writeString(ByteBuf out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IllegalArgumentException("String too long: " + bytes.length);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static String readString(ByteBuf in) {
        int length = readVarInt(in);
        if (length < 0 || length > MAX_STRING_BYTES) throw new DecoderException("String too long: " + length);
        return new String(readBytes(in, length), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ByteBuf in, int length) {
        if (in.readableBytes() < length) throw new DecoderException("Truncated packet");
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        return bytes;
    }

    private static List<String> readStrings(ByteBuf in) {
        int size = readListSize(in);
        List<String> strings = new ArrayList<>(size);
        for (int i = 0; i < size; i++) strings.add(readString(in));
        return strings;
    }

    private static int readListSize(ByteBuf in) {
        int size = readVarInt(in);
        if (size < 0 || size > MAX_LIST_SIZE) throw new DecoderException("List too long: " + size);
        return size;
    }

    private static void writeUuid(ByteBuf out, UUID uuid) {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuf in) {
        return new UUID(in.readLong(), in.readLong());
    }

    private static <E extends Enum<E>> void writeEnum(ByteBuf out, E value) {
        out.writeByte(value.ordinal());
    }

    private static <E extends Enum<E>> E readEnum(ByteBuf in, Class<E> type) {
        E[] values = type.getEnumConstants();
        int ordinal = in.readUnsignedByte();
        if (ordinal >= values.length) throw new DecoderException("Bad " + type.getSimpleName() + " ordinal " + ordinal);
        return values[ordinal];
    }
}
