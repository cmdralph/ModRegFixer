package io.github.cmdralph.modregfixer.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.cmdralph.modregfixer.mixin.ClientCommonPacketListenerImplAccessor;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;

/**
 * Snapshot of what a server provides, captured while the client finishes the configuration
 * phase.
 *
 * <p><b>Registry presence is authoritative.</b> The namespaces and elements here come from the
 * registry and tag data the server actually sent, not from a mod list (vanilla, Paper, Folia and
 * Velocity never send one). Brand and plugin channels are recorded for diagnostics only; the one
 * channel that changes behaviour is Fabric's registry-sync channel, which tells us the server
 * runs Fabric API and keeps static registries (items, blocks...) in sync with the client itself.
 */
public final class ServerCapabilities {
	/** Fabric API's server registers this receiver whenever it runs registry sync. */
	public static final Identifier FABRIC_REGISTRY_SYNC_CHANNEL = Identifier.fromNamespaceAndPath("fabric", "registry/sync/complete");

	private final boolean remote;
	private final String address;
	private final @Nullable String brand;
	private final boolean fabricRegistrySync;
	private final Set<Identifier> serverChannels;
	private final Map<Identifier, Set<String>> registryNamespaces;
	private final Map<Identifier, Integer> registryElementCounts;
	private final Set<String> tagNamespaces;
	private final Set<String> evidencedNamespaces;
	private final List<Identifier> absentSynchronizedRegistries;
	private final Map<String, Integer> clientOnlyNamespaces;
	private final RegistryAccess.Frozen registries;

	private ServerCapabilities(Builder builder) {
		this.remote = builder.remote;
		this.address = builder.address;
		this.brand = builder.brand;
		this.fabricRegistrySync = builder.fabricRegistrySync;
		this.serverChannels = Collections.unmodifiableSet(builder.serverChannels);
		this.registryNamespaces = Collections.unmodifiableMap(builder.registryNamespaces);
		this.registryElementCounts = Collections.unmodifiableMap(builder.registryElementCounts);
		this.tagNamespaces = Collections.unmodifiableSet(builder.tagNamespaces);
		this.evidencedNamespaces = Collections.unmodifiableSet(builder.evidencedNamespaces);
		this.absentSynchronizedRegistries = List.copyOf(builder.absentSynchronizedRegistries);
		this.clientOnlyNamespaces = Collections.unmodifiableMap(builder.clientOnlyNamespaces);
		this.registries = builder.registries;
	}

	/**
	 * Builds the snapshot. Must run while the configuration phase is still active (inside
	 * {@code updateComponents}), because Fabric's channel information is only available then.
	 */
	public static ServerCapabilities capture(CollectionContext context, RegistryAccess.Frozen registries, @Nullable ClientConfigurationPacketListenerImpl listener) {
		Builder builder = new Builder(context.remote(), registries);

		// --- connection identity (diagnostics) ---
		if (listener != null) {
			ClientCommonPacketListenerImplAccessor accessor = (ClientCommonPacketListenerImplAccessor) (Object) listener;
			ServerData serverData = accessor.modregfixer$getServerData();
			builder.address = serverData != null ? serverData.ip : "unknown (no server entry)";
			builder.brand = accessor.modregfixer$getServerBrand();
		}

		// --- plugin / mod channels the server can receive on ---
		builder.serverChannels.addAll(readServerChannels());
		builder.fabricRegistrySync = builder.serverChannels.contains(FABRIC_REGISTRY_SYNC_CHANNEL);

		// --- registry contents the server actually sent ---
		context.sentElements().forEach((registryKey, ids) -> {
			Set<String> namespaces = new TreeSet<>();
			ids.forEach(id -> namespaces.add(id.getNamespace()));
			builder.registryNamespaces.put(registryKey.identifier(), namespaces);
			builder.registryElementCounts.put(registryKey.identifier(), ids.size());
			builder.evidencedNamespaces.addAll(namespaces);
		});

		// --- tags the server sent (names and members) ---
		for (ResourceKey<? extends Registry<?>> registryKey : context.sentTagRegistries()) {
			lookupRegistry(registries, registryKey).ifPresent(registry -> collectTagNamespaces(registry, builder.tagNamespaces));
		}

		builder.evidencedNamespaces.addAll(builder.tagNamespaces);
		builder.evidencedNamespaces.add(Identifier.DEFAULT_NAMESPACE);

		// --- synchronized registries the client knows about but the server did not send ---
		for (RegistryDataLoader.RegistryData<?> data : RegistryDataLoader.SYNCHRONIZED_REGISTRIES) {
			if (!context.sentElements().containsKey(data.key())) {
				builder.absentSynchronizedRegistries.add(data.key().identifier());
			}
		}

		// --- client-side registry content with no trace on the server ---
		for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
			for (Identifier id : registry.keySet()) {
				String namespace = id.getNamespace();

				if (!builder.evidencedNamespaces.contains(namespace)) {
					builder.clientOnlyNamespaces.merge(namespace, 1, Integer::sum);
				}
			}
		}

		return new ServerCapabilities(builder);
	}

	/**
	 * Fallback used when {@link #capture} itself fails: no diagnostics, and every namespace other
	 * than {@code minecraft} is treated as unknown. Cannot fail.
	 */
	public static ServerCapabilities minimal(boolean remote, RegistryAccess.Frozen registries) {
		Builder builder = new Builder(remote, registries);
		builder.evidencedNamespaces.add(Identifier.DEFAULT_NAMESPACE);
		return new ServerCapabilities(builder);
	}

	private static Set<Identifier> readServerChannels() {
		try {
			return Set.copyOf(ClientConfigurationNetworking.getSendable());
		} catch (IllegalStateException notConfiguring) {
			// Documented behaviour of the API when no configuration phase is active.
			return Set.of();
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Optional<Registry<?>> lookupRegistry(RegistryAccess registries, ResourceKey<? extends Registry<?>> key) {
		return registries.lookup((ResourceKey) key);
	}

	private static <T> void collectTagNamespaces(Registry<T> registry, Set<String> out) {
		registry.getTags().forEach(tag -> {
			out.add(tag.key().location().getNamespace());
			tag.stream().forEach(holder -> holder.unwrapKey().ifPresent(key -> out.add(key.identifier().getNamespace())));
		});
	}

	// ---------------------------------------------------------------------------------------
	// Queries
	// ---------------------------------------------------------------------------------------

	/** {@code false} for the in-memory connection to the integrated server. */
	public boolean remote() {
		return remote;
	}

	/** The address as typed in the server list, or a placeholder when unknown. */
	public String address() {
		return address;
	}

	/** The server brand, e.g. {@code vanilla}, {@code Paper}, {@code DonutFolia (SchengenVelocity)}. */
	public @Nullable String brand() {
		return brand;
	}

	/** Whether the server runs Fabric API registry sync (and therefore agrees with us on static registries). */
	public boolean fabricRegistrySync() {
		return fabricRegistrySync;
	}

	public Set<Identifier> serverChannels() {
		return serverChannels;
	}

	/** Registry id → namespaces of the entries the server sent for it. */
	public Map<Identifier, Set<String>> registryNamespaces() {
		return registryNamespaces;
	}

	public Map<Identifier, Integer> registryElementCounts() {
		return registryElementCounts;
	}

	public Set<String> tagNamespaces() {
		return tagNamespaces;
	}

	/** Every namespace the server's own data shows evidence of (always includes {@code minecraft}). */
	public Set<String> evidencedNamespaces() {
		return evidencedNamespaces;
	}

	/** Synchronized (datapack) registries the client knows but the server did not send. */
	public List<Identifier> absentSynchronizedRegistries() {
		return absentSynchronizedRegistries;
	}

	/** Namespace → number of client static-registry entries in a namespace the server shows no trace of. */
	public Map<String, Integer> clientOnlyNamespaces() {
		return clientOnlyNamespaces;
	}

	public boolean isNamespaceEvidenced(String namespace) {
		return evidencedNamespaces.contains(namespace);
	}

	/**
	 * Whether a registry entry is available on this server.
	 * <ul>
	 *     <li>Synchronized (datapack) registries: exact. The server's registry is consulted.</li>
	 *     <li>Static registries (items, blocks...): exact on Fabric registry-sync servers; otherwise
	 *     an entry counts as supported if it is vanilla or its namespace appears in the server's data.</li>
	 * </ul>
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public boolean isSupported(ResourceKey<?> key) {
		ResourceKey registryKey = key.registryKey();

		if (RegistrySynchronization.isNetworkable(registryKey)) {
			Optional<Registry<?>> registry = lookupRegistry(registries, registryKey);
			return registry.isPresent() && ((Registry) registry.get()).containsKey((ResourceKey) key);
		}

		return fabricRegistrySync || isNamespaceEvidenced(key.identifier().getNamespace());
	}

	private static final class Builder {
		private final boolean remote;
		private final RegistryAccess.Frozen registries;
		private String address = "unknown";
		private @Nullable String brand;
		private boolean fabricRegistrySync;
		private final Set<Identifier> serverChannels = new TreeSet<>();
		private final Map<Identifier, Set<String>> registryNamespaces = new TreeMap<>();
		private final Map<Identifier, Integer> registryElementCounts = new TreeMap<>();
		private final Set<String> tagNamespaces = new TreeSet<>();
		private final Set<String> evidencedNamespaces = new TreeSet<>();
		private final List<Identifier> absentSynchronizedRegistries = new ArrayList<>();
		private final Map<String, Integer> clientOnlyNamespaces = new TreeMap<>();

		private Builder(boolean remote, RegistryAccess.Frozen registries) {
			this.remote = remote;
			this.registries = registries;
		}
	}
}
