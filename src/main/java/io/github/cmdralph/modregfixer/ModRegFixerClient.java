package io.github.cmdralph.modregfixer;

import io.github.cmdralph.modregfixer.command.ModRegFixerCommand;
import io.github.cmdralph.modregfixer.compat.CreativeTabFilter;
import io.github.cmdralph.modregfixer.config.ModRegFixerConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. The actual compatibility work happens in the mixins; this class only loads
 * the config and wires up connection events, the creative-menu filter and the client command.
 */
public final class ModRegFixerClient implements ClientModInitializer {
	public static final String MOD_ID = "modregfixer";
	public static final Logger LOGGER = LoggerFactory.getLogger("ModRegFixer");

	@Override
	public void onInitializeClient() {
		ModRegFixerConfig.load();

		ClientConfigurationConnectionEvents.INIT.register((listener, client) -> CompatibilityManager.onConfigurationInit(listener));
		ClientConfigurationConnectionEvents.DISCONNECT.register((listener, client) -> CompatibilityManager.onDisconnect());
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> CompatibilityManager.onDisconnect());

		CreativeTabFilter.register();
		ClientCommandRegistrationCallback.EVENT.register(ModRegFixerCommand::register);

		ModRegFixerConfig config = ModRegFixerConfig.get();
		LOGGER.info("[ModRegFixer] Ready (enabled={}, debug={}, hideUnsupportedCreativeItems={}). Singleplayer is never affected.",
				config.enabled(), config.debug(), config.hideUnsupportedCreativeItems());
	}

	public static boolean isDebug() {
		return ModRegFixerConfig.get().debug();
	}
}
