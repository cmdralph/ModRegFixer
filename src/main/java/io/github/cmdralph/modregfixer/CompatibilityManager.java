package io.github.cmdralph.modregfixer;

import java.lang.ref.WeakReference;
import java.util.Optional;
import java.util.function.Supplier;

import io.github.cmdralph.modregfixer.compat.CollectionContext;
import io.github.cmdralph.modregfixer.compat.CompatibilityReport;
import io.github.cmdralph.modregfixer.compat.ComponentBuildSession;
import io.github.cmdralph.modregfixer.compat.ModAttribution;
import io.github.cmdralph.modregfixer.compat.ServerCapabilities;
import io.github.cmdralph.modregfixer.config.ModRegFixerConfig;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Central state of ModRegFixer and its public API.
 *
 * <h2>Lifecycle of one connection</h2>
 * <ol>
 *     <li>Configuration starts: {@link #onConfigurationInit} remembers the listener (for brand/address).</li>
 *     <li>The server sends registries and tags. {@code RegistryDataCollectorMixin} records what was sent.</li>
 *     <li>{@code ClientboundFinishConfigurationPacket} arrives and the client calls
 *     {@code RegistryDataCollector.collectGameRegistries}, which ModRegFixer wraps with
 *     {@link #runCollection}. Inside it, vanilla loads and freezes the server's registries and
 *     then calls {@code updateComponents}, which ModRegFixer wraps with {@link #runComponentUpdate}.</li>
 *     <li>{@link #runComponentUpdate} snapshots the server's capabilities and, <em>for remote
 *     connections only</em>, opens a {@link ComponentBuildSession} for the duration of vanilla's
 *     component build. While it is open, {@link io.github.cmdralph.modregfixer.compat.ComponentGuards}
 *     leave out components that reference content the server does not have.</li>
 *     <li>The result is published as {@link #currentReport()} until disconnect.</li>
 * </ol>
 *
 * <p>The build session lives in a {@link ThreadLocal} that is set only for the duration of that
 * one call on the client's packet thread. The integrated server builds components on its own
 * background threads and never sees it, so singleplayer behaves exactly like vanilla.
 */
public final class CompatibilityManager {
	private static final ThreadLocal<CollectionContext> COLLECTION = new ThreadLocal<>();
	private static final ThreadLocal<ComponentBuildSession> ACTIVE_BUILD = new ThreadLocal<>();

	private static volatile @Nullable CompatibilityReport current;
	private static volatile @Nullable CompatibilityReport last;
	private static volatile WeakReference<ClientConfigurationPacketListenerImpl> configurationListener = new WeakReference<>(null);

	private CompatibilityManager() {
	}

	// =======================================================================================
	// Public API: safe to call from other mods, from the client thread.
	// =======================================================================================

	/** {@code true} while connected to a remote server (dedicated, Paper, Folia, Velocity, LAN guest, Realms). */
	public static boolean isMultiplayer() {
		return current != null;
	}

	/** {@code true} when not connected to a remote server (title screen or singleplayer). */
	public static boolean isSingleplayerOrOffline() {
		return current == null;
	}

	/** Capabilities of the remote server currently connected to, if any. */
	public static Optional<ServerCapabilities> serverCapabilities() {
		CompatibilityReport report = current;
		return report == null ? Optional.empty() : Optional.of(report.capabilities());
	}

	/**
	 * Whether the given registry entry is provided by the current server. Always {@code true} in
	 * singleplayer and when not connected. See {@link ServerCapabilities#isSupported} for precision.
	 */
	public static boolean isServerContentSupported(ResourceKey<?> key) {
		CompatibilityReport report = current;
		return report == null || report.capabilities().isSupported(key);
	}

	/** Whether the current server's data shows any trace of a namespace. Always {@code true} offline. */
	public static boolean isServerNamespaceSupported(String namespace) {
		CompatibilityReport report = current;
		return report == null || report.capabilities().isNamespaceEvidenced(namespace);
	}

	/**
	 * Whether an item should be hidden from the creative menu on the current server: the server
	 * does not run Fabric registry sync and shows no trace of the item's mod, so picking it would
	 * send the server an item id it does not know.
	 */
	public static boolean shouldHideFromCreative(ItemStack stack) {
		CompatibilityReport report = current;
		ModRegFixerConfig config = ModRegFixerConfig.get();

		if (report == null || !config.enabled() || !config.hideUnsupportedCreativeItems()) {
			return false;
		}

		ServerCapabilities capabilities = report.capabilities();
		return !capabilities.fabricRegistrySync()
				&& !capabilities.isNamespaceEvidenced(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
	}

	/** {@code true} only while a remote component build is running on the current thread. */
	public static boolean isComponentFilteringActive() {
		return ACTIVE_BUILD.get() != null;
	}

	/** Report for the server currently connected to, or empty in singleplayer/offline. */
	public static Optional<CompatibilityReport> currentReport() {
		return Optional.ofNullable(current);
	}

	/** The most recent remote report, kept after disconnecting (for {@code /modregfixer report}). */
	public static Optional<CompatibilityReport> lastReport() {
		return Optional.ofNullable(last);
	}

	// =======================================================================================
	// Internal hooks: called from mixins and event listeners.
	// =======================================================================================

	/** The build session of the current thread, or {@code null} outside a remote component build. */
	public static @Nullable ComponentBuildSession activeBuild() {
		return ACTIVE_BUILD.get();
	}

	public static void onConfigurationInit(ClientConfigurationPacketListenerImpl listener) {
		configurationListener = new WeakReference<>(listener);
		// A new configuration phase (new connection or a proxy switching backend servers) invalidates
		// the previous server's state until the new registries have been analysed.
		current = null;
	}

	public static void onDisconnect() {
		current = null;
		configurationListener = new WeakReference<>(null);
	}

	/** Wraps {@code RegistryDataCollector.collectGameRegistries}: makes what the server sent visible to {@link #runComponentUpdate}. */
	public static <T> T runCollection(CollectionContext context, Supplier<T> collect) {
		CollectionContext previous = COLLECTION.get();
		COLLECTION.set(context);

		try {
			return collect.get();
		} finally {
			restore(COLLECTION, previous);
		}
	}

	/** Wraps {@code RegistryDataCollector.updateComponents(frozenRegistries, includeSharedRegistries)}. */
	public static void runComponentUpdate(RegistryAccess.Frozen registries, boolean includeSharedRegistries, Runnable update) {
		CollectionContext context = COLLECTION.get();

		// In-memory connection to the integrated server (singleplayer, LAN host): the server has every
		// mod the client has, and vanilla only refreshes synchronized registries here. Unchanged.
		if (context == null || !context.remote() || !includeSharedRegistries) {
			if (context != null && !context.remote()) {
				current = null;
			}

			update.run();
			return;
		}

		if (!ModRegFixerConfig.get().enabled()) {
			ModRegFixerClient.LOGGER.info("[ModRegFixer] Disabled in config; using vanilla behaviour for this server.");
			current = null;
			update.run();
			return;
		}

		ServerCapabilities capabilities = captureCapabilities(context, registries);
		ComponentBuildSession session = new ComponentBuildSession(capabilities);
		ComponentBuildSession previous = ACTIVE_BUILD.get();
		ACTIVE_BUILD.set(session);

		try {
			update.run();
		} catch (RuntimeException failure) {
			// Diagnostics only: the failure is rethrown unchanged, so Minecraft handles it exactly as it
			// would without this mod (no partially-applied state is left behind; see logUnfilterableFailure).
			logUnfilterableFailure(session, failure);
			throw failure;
		} finally {
			restore(ACTIVE_BUILD, previous);
		}

		CompatibilityReport report = new CompatibilityReport(capabilities, session.skipped());
		current = report;
		last = report;
		report.logSummary(ModRegFixerClient.LOGGER);

		if (ModRegFixerClient.isDebug()) {
			report.logDetails(ModRegFixerClient.LOGGER);
		}
	}

	/** Called for every element key before its initializers run, to annotate failure diagnostics. */
	public static void noteCurrentElement(Object key) {
		ComponentBuildSession session = ACTIVE_BUILD.get();

		if (session != null && key instanceof ResourceKey<?> resourceKey) {
			session.setCurrentElement(resourceKey);
		}
	}

	private static ServerCapabilities captureCapabilities(CollectionContext context, RegistryAccess.Frozen registries) {
		try {
			return ServerCapabilities.capture(context, registries, configurationListener.get());
		} catch (RuntimeException e) {
			// The analysis only feeds diagnostics and the creative filter; component guarding does not
			// depend on it. Never let a reporting problem stop the player from joining.
			ModRegFixerClient.LOGGER.error("[ModRegFixer] Could not fully analyse the server's registries; continuing with reduced diagnostics", e);
			return ServerCapabilities.minimal(context.remote(), registries);
		}
	}

	private static void logUnfilterableFailure(ComponentBuildSession session, RuntimeException failure) {
		ResourceKey<?> element = session.currentElement();
		String elementText = element == null ? "an unknown registry element" : element.identifier().toString();
		String owner = element == null ? "unknown" : ModAttribution.describe(element.identifier().getNamespace());

		ModRegFixerClient.LOGGER.error("""
						[ModRegFixer] ================================================================
						[ModRegFixer] Could not make this server connection safe.
						[ModRegFixer] While building default components for {} (from {}),
						[ModRegFixer] Minecraft failed with: {}
						[ModRegFixer] This content was not registered through Item.Properties' delayed component
						[ModRegFixer] methods (or Fabric's DefaultItemComponentEvents), so ModRegFixer cannot remove
						[ModRegFixer] just that part without risking inconsistent item data. The connection is
						[ModRegFixer] aborted exactly as it would be without ModRegFixer. Nothing was modified.
						[ModRegFixer] Please report this, including the lines below, at
						[ModRegFixer] https://github.com/cmdralph/ModRegFixer/issues
						[ModRegFixer] ================================================================""",
				elementText, owner, failure.toString());

		new CompatibilityReport(session.capabilities(), session.skipped()).logDetails(ModRegFixerClient.LOGGER);
	}

	private static <T> void restore(ThreadLocal<T> local, @Nullable T previous) {
		if (previous == null) {
			local.remove();
		} else {
			local.set(previous);
		}
	}
}
