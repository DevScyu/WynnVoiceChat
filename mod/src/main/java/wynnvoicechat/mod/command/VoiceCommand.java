package wynnvoicechat.mod.command;

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
import wynnvoicechat.mod.VoiceMod;
import wynnvoicechat.mod.session.VoiceRoster;
import wynnvoicechat.protocol.CallAction;
import wynnvoicechat.protocol.Packet;
import wynnvoicechat.protocol.Peer;
import wynnvoicechat.protocol.VoiceTier;

public final class VoiceCommand {
    private VoiceCommand() {}

    public static void register(VoiceMod mod) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = dispatcher.register(literal(VoiceMod.MOD_ID)
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
                    .then(literal("call").then(argument("player", StringArgumentType.word())
                            .executes(context -> request(context, mod, new Packet.Call(StringArgumentType.getString(context, "player"), CallAction.INVITE)))))
                    .then(literal("accept").executes(context -> request(context, mod, new Packet.Call("", CallAction.ACCEPT))))
                    .then(literal("decline").executes(context -> request(context, mod, new Packet.Call("", CallAction.DECLINE))))
                    .then(literal("hangup").executes(context -> request(context, mod, new Packet.Call("", CallAction.HANGUP))))
                    .then(literal("dnd")
                            .then(literal("on").executes(context -> setDnd(context, mod, true)))
                            .then(literal("off").executes(context -> setDnd(context, mod, false))))
                    .then(literal("sounds")
                            .then(literal("on").executes(context -> setSounds(context, mod, true)))
                            .then(literal("off").executes(context -> setSounds(context, mod, false))))
                    .then(literal("hud")
                            .then(literal("on").executes(context -> setHud(context, mod, true)))
                            .then(literal("off").executes(context -> setHud(context, mod, false))))
                    .then(literal("who").executes(context -> who(context, mod)))
                    .then(literal("blocks").executes(context -> request(context, mod, new Packet.BlockList())))
                    .then(literal("terms").executes(context -> openPage("terms")))
                    .then(literal("privacy").executes(context -> openPage("privacy")))
                    .then(literal("enable").executes(context -> setEnabled(context, mod, true)))
                    .then(literal("disable").executes(context -> setEnabled(context, mod, false)))
                    .executes(VoiceCommand::usage));
            // redirect skips the target's own executes, so the bare alias needs usage again
            dispatcher.register(literal(VoiceMod.ALIAS).executes(VoiceCommand::usage).redirect(root));
        });
    }

    private static int openPage(String path) {
        VoiceMod.openPage(path);
        return 1;
    }

    private static int usage(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Component.translatable("wynnvoicechat.command.usage", VoiceMod.COMMAND).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int setTier(CommandContext<FabricClientCommandSource> context, VoiceMod mod) {
        String raw = StringArgumentType.getString(context, "tier").toUpperCase(Locale.ROOT);
        VoiceTier tier = Arrays.stream(VoiceTier.values()).filter(candidate -> candidate.name().equals(raw)).findFirst().orElse(null);
        if (tier == null) {
            context.getSource().sendError(Component.translatable("wynnvoicechat.command.badTier"));
            return 1;
        }
        mod.setTier(tier);
        context.getSource().sendFeedback(Component.translatable("wynnvoicechat.command.tierSet", tier.name()).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int who(CommandContext<FabricClientCommandSource> context, VoiceMod mod) {
        List<Peer> peers = mod.roster();
        if (peers == null) {
            context.getSource().sendError(Component.translatable("wynnvoicechat.command.notConnected"));
            return 1;
        }
        if (mod.dnd()) context.getSource().sendFeedback(Component.translatable("wynnvoicechat.who.dnd").withStyle(ChatFormatting.YELLOW));
        if (peers.isEmpty()) {
            context.getSource().sendFeedback(Component.translatable("wynnvoicechat.who.empty").withStyle(ChatFormatting.AQUA));
            return 1;
        }
        for (String line : VoiceRoster.lines(peers, key -> I18n.get("wynnvoicechat.who." + key))) {
            context.getSource().sendFeedback(Component.literal(line).withStyle(ChatFormatting.AQUA));
        }
        return 1;
    }

    private static int request(CommandContext<FabricClientCommandSource> context, VoiceMod mod, Packet packet) {
        if (!mod.request(packet)) context.getSource().sendError(Component.translatable("wynnvoicechat.command.notConnected"));
        return 1;
    }

    private static int setGuildChannel(CommandContext<FabricClientCommandSource> context, VoiceMod mod, boolean on) {
        mod.setGuildChannel(on);
        context.getSource().sendFeedback(Component.translatable(on ? "wynnvoicechat.command.guildOn" : "wynnvoicechat.command.guildOff").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int setDnd(CommandContext<FabricClientCommandSource> context, VoiceMod mod, boolean on) {
        mod.setDnd(on);
        context.getSource().sendFeedback(Component.translatable(on ? "wynnvoicechat.command.dndOn" : "wynnvoicechat.command.dndOff").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int setHud(CommandContext<FabricClientCommandSource> context, VoiceMod mod, boolean on) {
        mod.setHud(on);
        context.getSource().sendFeedback(Component.translatable(on ? "wynnvoicechat.command.hudOn" : "wynnvoicechat.command.hudOff").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int setSounds(CommandContext<FabricClientCommandSource> context, VoiceMod mod, boolean on) {
        mod.setSounds(on);
        context.getSource().sendFeedback(Component.translatable(on ? "wynnvoicechat.command.soundsOn" : "wynnvoicechat.command.soundsOff").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int setEnabled(CommandContext<FabricClientCommandSource> context, VoiceMod mod, boolean enabled) {
        mod.setEnabled(enabled);
        context.getSource().sendFeedback(Component.translatable(enabled ? "wynnvoicechat.command.enabled" : "wynnvoicechat.command.disabled").withStyle(ChatFormatting.GREEN));
        return 1;
    }
}
