package wynnvoice.mod;

import java.io.IOException;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wynnvoice.mod.config.VoiceConfig;
import wynnvoice.mod.net.VoiceClient;
import wynnvoice.mod.wynn.WorldTracker;
import wynnvoice.protocol.AuthStatus;
import wynnvoice.protocol.Packet;

public final class VoiceMod implements ClientModInitializer {
    public static final String MOD_ID = "wynnvoice";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private VoiceConfig config;
    private VoiceClient client;
    private final WorldTracker worldTracker = new WorldTracker();
    private boolean onWynncraft;
    private boolean refused;

    @Override
    public void onInitializeClient() {
        try {
            config = VoiceConfig.load(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json"));
        } catch (IOException e) {
            LOG.error("Could not load config, voice disabled", e);
            return;
        }
        ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> {
            ServerData server = handler.getServerData();
            onWynncraft = server != null && isWynncraft(server.ip);
            refused = false;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
            onWynncraft = false;
            setWorld(null);
        });
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            if (!onWynncraft || minecraft.getConnection() == null) return;
            PlayerInfo entry = minecraft.getConnection().getPlayerInfo(WorldTracker.TAB_LIST_ENTRY);
            Component name = entry == null ? null : entry.getTabListDisplayName();
            setWorld(name == null ? null : name.getString());
        });
    }

    static boolean isWynncraft(String address) {
        String host = address.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "");
        return host.equals("wynncraft.com") || host.endsWith(".wynncraft.com");
    }

    private void setWorld(String tabListDisplayName) {
        if (!worldTracker.update(tabListDisplayName)) return;
        WorldTracker.State state = worldTracker.state();
        LOG.info("World state: {}", state);
        if (!state.onWorld()) {
            refused = false;
            disconnect();
        } else if (client == null && !refused) {
            connect();
        }
    }

    private void connect() {
        Minecraft minecraft = Minecraft.getInstance();
        VoiceClient.Identity identity = new VoiceClient.Identity(
                minecraft.getUser().getName(),
                minecraft.getUser().getProfileId(),
                FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().getMetadata().getVersion().getFriendlyString());
        VoiceClient.SessionJoiner joiner = serverId -> minecraft.services().sessionService()
                .joinServer(minecraft.getUser().getProfileId(), minecraft.getUser().getAccessToken(), serverId);
        VoiceClient[] self = new VoiceClient[1];
        self[0] = client = new VoiceClient(identity, joiner, new VoiceClient.Listener() {
            @Override
            public void onAuthResult(AuthStatus status) {
                LOG.info("Relay auth result: {}", status);
                if (status != AuthStatus.OK) minecraft.execute(() -> refuse(status));
            }

            @Override
            public void onPacket(Packet packet) {
                LOG.debug("Relay packet: {}", packet);
            }

            @Override
            public void onClosed() {
                LOG.info("Relay connection closed");
                minecraft.execute(() -> {
                    if (client == self[0]) disconnect();
                });
            }
        });
        client.connect(config.relayHost, config.relayPort);
    }

    private void refuse(AuthStatus status) {
        if (refused || client == null) return;
        refused = true;
        String reason = switch (status) {
            case VERSION_MISMATCH -> "your mod version is not supported by the relay, please update";
            case BANNED -> "you are banned from voice chat";
            case DISABLED -> "the relay is currently disabled";
            case BAD_SESSION -> "Mojang did not confirm your session";
            case SESSION_UNAVAILABLE -> "Mojang's session server could not be reached";
            case OK -> throw new IllegalArgumentException();
        };
        Minecraft.getInstance().gui.getChat().addMessage(
                Component.literal("[WynnVoice] Voice chat unavailable: " + reason).withStyle(ChatFormatting.RED));
        disconnect();
    }

    private void disconnect() {
        if (client == null) return;
        client.shutdown();
        client = null;
    }
}
