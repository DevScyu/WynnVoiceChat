package wynnvoice.mod;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wynnvoice.mod.config.VoiceConfig;
import wynnvoice.mod.net.VoiceClient;
import wynnvoice.mod.session.VoiceSession;
import wynnvoice.mod.svc.VoiceChatBridge;
import wynnvoice.mod.svc.VoiceChatPayloads;
import wynnvoice.mod.wynn.PartyTracker;
import wynnvoice.mod.wynn.WorldTracker;
import wynnvoice.protocol.AuthStatus;
import wynnvoice.protocol.Packet;
import wynnvoice.protocol.SocialKind;

public final class VoiceMod implements ClientModInitializer {
    public static final String MOD_ID = "wynnvoice";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    private static final int TICKS_PER_POSITION = 5; // 4 Hz
    private static final int POSITIONS_PER_STATE_SYNC = 20; // ~5 s, catches players who loaded in since the last Peers
    private static volatile VoiceMod instance;

    private VoiceConfig config;
    private VoiceClient client;
    private VoiceSession session;
    private String connectedWorld;
    private String lastWorld;
    private final WorldTracker worldTracker = new WorldTracker();
    private PartyTracker party;
    private volatile boolean onWynncraft;
    private boolean refused;
    private boolean warnedSvcVersion;
    private boolean svcDisabled;
    private int tickCounter;
    private int positionCounter;

    @Override
    public void onInitializeClient() {
        try {
            config = VoiceConfig.load(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json"));
        } catch (IOException e) {
            LOG.error("Could not load config, voice disabled", e);
            return;
        }
        instance = this;
        ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> {
            ServerData server = handler.getServerData();
            onWynncraft = server != null && isWynncraft(server.ip);
            refused = false;
            warnedSvcVersion = false;
            party = new PartyTracker(minecraft.getUser().getName(), () -> sendCommand("party list"),
                    (action, names) -> {
                        if (session != null) session.social(SocialKind.PARTY, action, names);
                    });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
            onWynncraft = false;
            setWorld(null);
            party = null;
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    static boolean isWynncraft(String address) {
        String host = address.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "");
        return host.equals("wynncraft.com") || host.endsWith(".wynncraft.com");
    }

    private void tick(Minecraft minecraft) {
        if (!onWynncraft || minecraft.getConnection() == null) return;
        PlayerInfo entry = minecraft.getConnection().getPlayerInfo(WorldTracker.TAB_LIST_ENTRY);
        Component name = entry == null ? null : entry.getTabListDisplayName();
        setWorld(name == null ? null : name.getString());

        if (session == null || !session.isActive() || minecraft.player == null || ++tickCounter < TICKS_PER_POSITION) return;
        tickCounter = 0;
        LocalPlayer player = minecraft.player;
        session.updatePosition((float) player.getX(), (float) player.getY(), (float) player.getZ());
        if (++positionCounter >= POSITIONS_PER_STATE_SYNC) {
            positionCounter = 0;
            session.syncStates();
        }
    }

    private void setWorld(String tabListDisplayName) {
        if (!worldTracker.update(tabListDisplayName)) return;
        WorldTracker.State state = worldTracker.state();
        LOG.info("World state: {}", state);
        boolean enteredWorld = state.onWorld() && !state.world().equals(lastWorld);
        lastWorld = state.onWorld() ? state.world() : null;
        if (party != null) {
            if (enteredWorld) party.requestList();
            else if (!state.onWorld()) party.reset();
        }
        if (!state.onWorld()) {
            refused = false;
            disconnect();
        } else if (client != null && !state.world().equals(connectedWorld)) {
            disconnect();
            connect(state);
        } else if (client == null && !refused) {
            connect(state);
        } else if (session != null) {
            session.setInstance(state.instance());
        }
    }

    private void connect(WorldTracker.State state) {
        Minecraft minecraft = Minecraft.getInstance();
        VoiceClient.Identity identity = new VoiceClient.Identity(
                minecraft.getUser().getName(),
                minecraft.getUser().getProfileId(),
                FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().getMetadata().getVersion().getFriendlyString());
        VoiceClient.SessionJoiner joiner = serverId -> minecraft.services().sessionService()
                .joinServer(minecraft.getUser().getProfileId(), minecraft.getUser().getAccessToken(), serverId);
        VoiceSession newSession = new VoiceSession(config.tier, new Effects(minecraft));
        newSession.setSvcDisabled(svcDisabled);
        VoiceClient[] self = new VoiceClient[1];
        self[0] = client = new VoiceClient(identity, joiner, new VoiceClient.Listener() {
            @Override
            public void onAuthResult(AuthStatus status) {
                LOG.info("Relay auth result: {}", status);
                minecraft.execute(() -> {
                    if (client != self[0]) return;
                    if (status == AuthStatus.OK) newSession.onAuthenticated(state.world(), worldTracker.state().instance());
                    else refuse(status);
                });
            }

            @Override
            public void onPacket(Packet packet) {
                minecraft.execute(() -> {
                    if (client == self[0]) newSession.onPacket(packet);
                });
            }

            @Override
            public void onClosed() {
                LOG.info("Relay connection closed");
                minecraft.execute(() -> {
                    if (client == self[0]) disconnect();
                });
            }
        });
        session = newSession;
        connectedWorld = state.world();
        client.connect(config.relayHost, config.relayPort);
    }

    private void refuse(AuthStatus status) {
        if (refused || client == null) return;
        refused = true;
        String reason = switch (status) {
            case VERSION_MISMATCH -> "your mod version is not supported by the relay, please update";
            case BANNED -> "you are banned from voice chat";
            case DISABLED -> "the relay is currently disabled";
            case NOT_ALLOWED -> "you are not on the relay's allowlist";
            case BAD_SESSION -> "Mojang did not confirm your session";
            case SESSION_UNAVAILABLE -> "Mojang's session server could not be reached";
            case OK -> throw new IllegalArgumentException();
        };
        chat("Voice chat unavailable: " + reason, ChatFormatting.RED);
        disconnect();
    }

    private void disconnect() {
        if (session != null) session.onClosed();
        session = null;
        connectedWorld = null;
        if (client == null) return;
        client.shutdown();
        client = null;
    }

    private static void chat(String message, ChatFormatting colour) {
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[WynnVoice] " + message).withStyle(colour));
    }

    private static void sendCommand(String command) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) connection.sendCommand(command);
    }

    /** Party bookkeeping from Wynncraft's chat; true hides the line (our own {@code /party list} response). */
    public static boolean interceptSystemChat(Component message) {
        VoiceMod mod = instance;
        if (mod == null || !mod.onWynncraft || mod.party == null) return false;
        return mod.party.onChat(message.getString(), PartyTracker.realName(message));
    }

    /** Wynncraft has no Simple Voice Chat server; its plugin messages are answered here and never sent. */
    public static boolean interceptServerbound(ServerboundCustomPayloadPacket packet) {
        VoiceMod mod = instance;
        if (mod == null || !mod.onWynncraft) return false;
        VoiceChatBridge.Intercepted intercepted = VoiceChatBridge.readServerbound(packet);
        if (intercepted == null) return false;
        Minecraft.getInstance().execute(() -> mod.onSvcPayload(intercepted));
        return true;
    }

    private void onSvcPayload(VoiceChatBridge.Intercepted intercepted) {
        Identifier channel = intercepted.channel();
        if (channel.equals(VoiceChatPayloads.CREATE_GROUP) || channel.equals(VoiceChatPayloads.SET_GROUP) || channel.equals(VoiceChatPayloads.LEAVE_GROUP)) {
            chat("Voice groups follow your Wynncraft party. Use /party instead.", ChatFormatting.YELLOW);
            return;
        }
        if (!intercepted.data().isReadable()) return;
        if (channel.equals(VoiceChatPayloads.REQUEST_SECRET)) {
            int version = VoiceChatPayloads.readRequestSecretVersion(intercepted.data());
            if (version != VoiceClient.SVC_COMPAT_VERSION && !warnedSvcVersion) {
                warnedSvcVersion = true;
                chat("Your Simple Voice Chat version is not supported, please update it", ChatFormatting.RED);
            }
        } else if (channel.equals(VoiceChatPayloads.UPDATE_STATE)) {
            svcDisabled = VoiceChatPayloads.readUpdateStateDisabled(intercepted.data());
            if (session != null) session.setSvcDisabled(svcDisabled);
        }
    }

    private record Effects(Minecraft minecraft) implements VoiceSession.Effects {
        @Override
        public void send(Packet packet) {
            VoiceClient client = instance.client;
            if (client != null) client.send(packet);
        }

        @Override
        public void chat(String message) {
            VoiceMod.chat(message, ChatFormatting.YELLOW);
        }

        @Override
        public void injectSecret(Packet.Secret secret) {
            UUID self = minecraft.getUser().getProfileId();
            VoiceChatBridge.inject(VoiceChatPayloads.SECRET, buf -> VoiceChatPayloads.writeSecret(
                    buf, secret.secret(), secret.port(), self, secret.range(), secret.keepAliveMs(), secret.host()));
        }

        @Override
        public void injectStates(List<VoiceChatPayloads.State> states) {
            VoiceChatBridge.inject(VoiceChatPayloads.STATES, buf -> VoiceChatPayloads.writeStates(buf, states));
        }

        @Override
        public Map<UUID, String> otherPlayers() {
            Map<UUID, String> others = new LinkedHashMap<>();
            if (minecraft.level == null) return others;
            for (Player other : minecraft.level.players()) {
                if (other != minecraft.player) others.put(other.getUUID(), other.getScoreboardName());
            }
            return others;
        }

        @Override
        public Set<String> party() {
            PartyTracker party = instance.party;
            return party == null ? Set.of() : party.members();
        }

        @Override
        public void injectPartyGroup(boolean joined) {
            if (joined) VoiceChatBridge.inject(VoiceChatPayloads.ADD_GROUP, buf -> VoiceChatPayloads.writeAddGroup(buf, VoiceSession.PARTY_GROUP, "Party"));
            VoiceChatBridge.inject(VoiceChatPayloads.JOINED_GROUP, buf -> VoiceChatPayloads.writeJoinedGroup(buf, joined ? VoiceSession.PARTY_GROUP : null));
        }
    }
}
