package io.github.cmdralph.modregfixer.command;

import java.util.Map;
import java.util.Optional;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.github.cmdralph.modregfixer.CompatibilityManager;
import io.github.cmdralph.modregfixer.ModRegFixerClient;
import io.github.cmdralph.modregfixer.compat.CompatibilityReport;
import io.github.cmdralph.modregfixer.compat.ModAttribution;
import io.github.cmdralph.modregfixer.compat.ServerCapabilities;
import io.github.cmdralph.modregfixer.compat.SkippedContent;
import io.github.cmdralph.modregfixer.config.ModRegFixerConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Client-side command (never sent to the server):
 * <ul>
 *     <li>{@code /modregfixer}: one-screen status of the current connection</li>
 *     <li>{@code /modregfixer report}: full report written to the log, short version in chat</li>
 *     <li>{@code /modregfixer debug on|off}: toggle verbose logging (saved to the config file)</li>
 * </ul>
 */
public final class ModRegFixerCommand {
	private static final int MAX_CHAT_ENTRIES = 8;

	private ModRegFixerCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(ClientCommands.literal("modregfixer")
				.executes(ModRegFixerCommand::status)
				.then(ClientCommands.literal("report").executes(ModRegFixerCommand::report))
				.then(ClientCommands.literal("debug")
						.then(ClientCommands.literal("on").executes(context -> setDebug(context, true)))
						.then(ClientCommands.literal("off").executes(context -> setDebug(context, false)))));
	}

	private static int status(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		Optional<CompatibilityReport> current = CompatibilityManager.currentReport();

		if (current.isEmpty()) {
			source.sendFeedback(prefix().append(Component.literal("Singleplayer / integrated server: all mod content is fully active, nothing is filtered.").withStyle(ChatFormatting.GREEN)));
			return Command.SINGLE_SUCCESS;
		}

		CompatibilityReport report = current.get();
		ServerCapabilities capabilities = report.capabilities();
		source.sendFeedback(prefix().append(Component.literal("Server " + capabilities.address()
				+ " (" + (capabilities.brand() == null ? "unknown brand" : capabilities.brand()) + ")").withStyle(ChatFormatting.WHITE)));
		source.sendFeedback(line("Fabric registry sync: " + (capabilities.fabricRegistrySync() ? "yes" : "no")));
		source.sendFeedback(line("Server data namespaces: " + String.join(", ", capabilities.evidencedNamespaces())));

		if (!capabilities.clientOnlyNamespaces().isEmpty()) {
			source.sendFeedback(line("Installed but not on this server: " + String.join(", ", capabilities.clientOnlyNamespaces().keySet())));
		}

		if (report.skipped().isEmpty()) {
			source.sendFeedback(line("No content needed filtering.").withStyle(ChatFormatting.GREEN));
		} else {
			source.sendFeedback(line("Filtered " + report.skipped().size() + " component(s):").withStyle(ChatFormatting.YELLOW));

			for (Map.Entry<String, Integer> entry : report.filteredByNamespace().entrySet()) {
				source.sendFeedback(line("  " + ModAttribution.describe(entry.getKey()) + ": " + entry.getValue()));
			}

			source.sendFeedback(line("Use /modregfixer report for details.").withStyle(ChatFormatting.GRAY));
		}

		return Command.SINGLE_SUCCESS;
	}

	private static int report(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		Optional<CompatibilityReport> report = CompatibilityManager.currentReport().or(CompatibilityManager::lastReport);

		if (report.isEmpty()) {
			source.sendFeedback(prefix().append(Component.literal("No remote server has been joined this session yet.")));
			return Command.SINGLE_SUCCESS;
		}

		report.get().logDetails(ModRegFixerClient.LOGGER);
		int shown = 0;

		for (SkippedContent entry : report.get().skipped()) {
			if (shown++ >= MAX_CHAT_ENTRIES) {
				source.sendFeedback(line("  ... and " + (report.get().skipped().size() - MAX_CHAT_ENTRIES) + " more"));
				break;
			}

			source.sendFeedback(line("  " + entry.element().identifier() + " -> " + entry.component() + " (missing " + entry.missing().describe() + ")"));
		}

		source.sendFeedback(prefix().append(Component.literal("Full report written to logs/latest.log").withStyle(ChatFormatting.GRAY)));
		return Command.SINGLE_SUCCESS;
	}

	private static int setDebug(CommandContext<FabricClientCommandSource> context, boolean enabled) {
		ModRegFixerConfig.set(ModRegFixerConfig.get().withDebug(enabled));
		context.getSource().sendFeedback(prefix().append(Component.literal("Debug logging " + (enabled ? "enabled" : "disabled") + ".")));
		return Command.SINGLE_SUCCESS;
	}

	private static MutableComponent prefix() {
		return Component.literal("[ModRegFixer] ").withStyle(ChatFormatting.AQUA);
	}

	private static MutableComponent line(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}
}
