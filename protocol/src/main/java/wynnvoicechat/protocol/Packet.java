package wynnvoicechat.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public sealed interface Packet {
    record Hello(int protocolVersion, int svcCompatVersion, String modVersion) implements Packet {}

    record AuthChallenge(byte[] serverId) implements Packet {
        public AuthChallenge {
            if (serverId.length != Protocol.SERVER_ID_BYTES) throw new IllegalArgumentException("serverId must be " + Protocol.SERVER_ID_BYTES + " bytes");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof AuthChallenge other && Arrays.equals(serverId, other.serverId);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(serverId);
        }
    }

    record Auth(String username, UUID uuid) implements Packet {}

    record AuthResult(AuthStatus status) implements Packet {}

    record Join(VoiceTier tier, String instance) implements Packet {}

    /** {@code maxTier} is the relay's audience cap; a higher tier in {@code Join} or {@code Update} is clamped to it. */
    record Secret(byte[] secret, String host, int port, double range, int keepAliveMs, VoiceTier maxTier) implements Packet {
        public Secret {
            if (secret.length != Protocol.SECRET_BYTES) throw new IllegalArgumentException("secret must be " + Protocol.SECRET_BYTES + " bytes");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Secret other
                    && Arrays.equals(secret, other.secret)
                    && host.equals(other.host)
                    && port == other.port
                    && range == other.range
                    && keepAliveMs == other.keepAliveMs
                    && maxTier == other.maxTier;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(secret) * 31 + host.hashCode();
        }
    }

    record Update(VoiceTier tier, String instance, boolean svcDisabled, boolean guildChannel, boolean dnd) implements Packet {}

    record World(String world) implements Packet {}

    record Position(float x, float y, float z) implements Packet {}

    record Social(SocialKind kind, SocialAction action, List<String> names) implements Packet {}

    record Peers(List<Peer> peers) implements Packet {}

    record Ended(EndReason reason, String message) implements Packet {}

    record Block(String targetName, boolean blocked) implements Packet {}

    record Report(String targetName, String reason) implements Packet {}

    record Result(ResultKind kind, boolean ok, String message) implements Packet {}

    record BlockList() implements Packet {}

    record BlockListResult(List<String> names) implements Packet {}

    /** Mute or unmute a fellow guild member in the guild channel; {@code hours} 0 means until unmuted. */
    record GuildMute(String targetName, boolean muted, int hours) implements Packet {}

    /** Friend call control; {@code targetName} only matters for {@code INVITE}. */
    record Call(String targetName, CallAction action) implements Packet {}

    record CallState(String peerName, CallStateKind state) implements Packet {}

    /** The player's guild prefix once the relay has looked it up; empty when they have no guild. */
    record Guild(String prefix) implements Packet {}
}
