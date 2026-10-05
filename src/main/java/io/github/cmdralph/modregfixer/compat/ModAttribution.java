package io.github.cmdralph.modregfixer.compat;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.resources.Identifier;

/**
 * Maps a registry namespace back to the installed mod that most likely owns it, for diagnostics.
 *
 * <p>Most mods use their mod id as namespace ({@code biomesoplenty} → Biomes O' Plenty). If no mod
 * has that id, the mod whose jar ships {@code data/<namespace>/} or {@code assets/<namespace>/}
 * is used instead.
 */
public final class ModAttribution {
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

	private ModAttribution() {
	}

	/** e.g. {@code "Biomes O' Plenty (biomesoplenty 26.3.0.0.13)"} or {@code "unknown mod (namespace 'foo')"}. */
	public static String describe(String namespace) {
		return CACHE.computeIfAbsent(namespace, ModAttribution::resolve);
	}

	private static String resolve(String namespace) {
		if (Identifier.DEFAULT_NAMESPACE.equals(namespace)) {
			return "Minecraft";
		}

		FabricLoader loader = FabricLoader.getInstance();
		Optional<ModContainer> direct = loader.getModContainer(namespace);

		if (direct.isPresent()) {
			return format(direct.get().getMetadata());
		}

		for (ModContainer container : loader.getAllMods()) {
			if (container.findPath("data/" + namespace).isPresent() || container.findPath("assets/" + namespace).isPresent()) {
				return format(container.getMetadata()) + " [namespace '" + namespace + "']";
			}
		}

		return "unknown mod (namespace '" + namespace + "')";
	}

	private static String format(ModMetadata metadata) {
		return metadata.getName() + " (" + metadata.getId() + " " + metadata.getVersion().getFriendlyString() + ")";
	}
}
