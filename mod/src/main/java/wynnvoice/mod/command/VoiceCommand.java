package wynnvoice.mod.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import wynnvoice.mod.VoiceMod;
import wynnvoice.mod.session.VoiceRoster;
import wynnvoice.protocol.Packet;
import wynnvoice.protocol.Peer;
import wynnvoice.protocol.VoiceTier;

public final class VoiceCommand {
    private VoiceCommand() {}

    public static void register(VoiceMod mod) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal(VoiceMod.MOD_ID)
                .then(literal("tier").then(argument("tier", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                Arrays.stream(VoiceTier.values()).map(tier -> tier.name().toLowerCase(Locale.ROOT)), builder))
                        .executes(context -> setTier(context, mod))))
                .then(literal("block").then(argument("player", StringArgumentType.word())
                        .executes(context -> request(context, mod, new Packet.Block(StringArgumentType.getString(context, "player"), true)))))
                .then(literal("unblock").then(argument("player", StringArgumentType.word())
                        .executes(context -> request(context, mod, new Packet.Block(StringArgumentType.getString(context, "player"), false)))))
                .then(literal("report").then(argument("player", StringArgumentType.word())
                        .executes(context -> request(context, mod, new Packet.Report(StringArgumentType.getString(context, "player"), "")))
                        .then(argument("reason", StringArgumentType.greedyString())
                                .executes(context -> request(context, mod, new Packet.Report(
                                        StringArgumentType.getString(context, "player"), StringArgumentType.getString(context, "reason")))))))
                .then(literal("guild")
                        .then(literal("on").executes(context -> setGuildChannel(context, mod, true)))
                        .then(literal("off").executes(context -> setGuildChannel(context, mod, false)))
                        .then(literal("mute").then(argument("player", StringArgumentType.word())
                                .executes(context -> request(context, mod, new Packet.GuildMute(StringArgumentType.getString(context, "player"), true, 0)))
                                .then(argument("hours", IntegerArgumentType.integer(1))
                                        .executes(context -> request(context, mod, new Packet.GuildMute(
                                                StringArgumentType.getString(context, "player"), true, IntegerArgumentType.getInteger(context, "hours")))))))
                        .then(literal("unmute").then(argument("player", StringArgumentType.word())
                                .executes(context -> request(context, mod, new Packet.GuildMute(StringArgumentType.getString(context, "player"), false, 0))))))
                .then(literal("who").executes(context -> who(context, mod)))
                .then(literal("blocks").executes(context -> request(context, mod, new Packet.BlockList())))
                .then(literal("enable").executes(context -> setEnabled(context, mod, true)))
                .then(literal("disable").executes(context -> setEnabled(context, mod, false)))
                .executes(context -> {
                    context.getSource().sendFeedback(Component.translatable("wynnvoice.command.usage", VoiceMod.COMMAND).withStyle(ChatFormatting.AQUA));
                    return 1;
                })));
    }

    private static int setTier(CommandContext<FabricClientCommandSource> context, VoiceMod mod) {
        String raw = StringArgumentType.getString(context, "tier").toUpperCase(Locale.ROOT);
        VoiceTier tier = Arrays.stream(VoiceTier.values()).filter(candidate -> candidate.name().equals(raw)).findFirst().orElse(null);
        if (tier == null) {
            context.getSource().sendError(Component.translatable("wynnvoice.command.badTier"));
            return 1;
        }
        mod.setTier(tier);
        context.getSource().sendFeedback(Component.translatable("wynnvoice.command.tierSet", tier.name()).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int who(CommandContext<FabricClientCommandSource> context, VoiceMod mod) {
        List<Peer> peers = mod.roster();
        if (peers == null) {
            context.getSource().sendError(Component.translatable("wynnvoice.command.notConnected"));
            return 1;
        }
        if (peers.isEmpty()) {
            context.getSource().sendFeedback(Component.translatable("wynnvoice.who.empty").withStyle(ChatFormatting.AQUA));
            return 1;
        }
        for (String line : VoiceRoster.lines(peers, key -> I18n.get("wynnvoice.who." + key))) {
            context.getSource().sendFeedback(Component.literal(line).withStyle(ChatFormatting.AQUA));
        }
        return 1;
    }

    private static int request(CommandContext<FabricClientCommandSource> context, VoiceMod mod, Packet packet) {
        if (!mod.request(packet)) context.getSource().sendError(Component.translatable("wynnvoice.command.notConnected"));
        return 1;
    }

    private static int setGuildChannel(CommandContext<FabricClientCommandSource> context, VoiceMod mod, boolean on) {
        mod.setGuildChannel(on);
        context.getSource().sendFeedback(Component.translatable(on ? "wynnvoice.command.guildOn" : "wynnvoice.command.guildOff").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int setEnabled(CommandContext<FabricClientCommandSource> context, VoiceMod mod, boolean enabled) {
        mod.setEnabled(enabled);
        context.getSource().sendFeedback(Component.translatable(enabled ? "wynnvoice.command.enabled" : "wynnvoice.command.disabled").withStyle(ChatFormatting.GREEN));
        return 1;
    }
}
