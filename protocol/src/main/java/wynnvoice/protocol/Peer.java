package wynnvoice.protocol;

import java.util.UUID;

/** {@code guildChannel}: the peer has the guild channel on, so a guild mate with it on too shares the guild group. */
public record Peer(UUID uuid, String name, boolean disabled, Relation relation, boolean reachable, boolean guildChannel) {}
