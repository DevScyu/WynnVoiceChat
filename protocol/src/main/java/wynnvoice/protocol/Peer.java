package wynnvoice.protocol;

import java.util.UUID;

public record Peer(UUID uuid, String name, boolean disabled, Relation relation, boolean reachable) {}
