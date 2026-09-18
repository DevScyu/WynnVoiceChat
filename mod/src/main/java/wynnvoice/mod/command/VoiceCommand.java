package wynnvoice.mod.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Arrays;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import wynnvoice.mod.VoiceMod;
import wynnvoice.protocol.VoiceTier;

public final class VoiceCommand {
    private VoiceCommand() {}

    public static void register(VoiceMod mod) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal(VoiceMod.MOD_ID)
                .then(literal("tier").then(argument("tier", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                Arrays.stream(VoiceTier.values()).map(tier -> tier.name().toLowerCase(Locale.ROOT)), builder))
                        .executes(context -> setTier(context, mod))))
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

    private static int setEnabled(CommandContext<FabricClientCommandSource> context, VoiceMod mod, boolean enabled) {
        mod.setEnabled(enabled);
        context.getSource().sendFeedback(Component.translatable(enabled ? "wynnvoice.command.enabled" : "wynnvoice.command.disabled").withStyle(ChatFormatting.GREEN));
        return 1;
    }
}
