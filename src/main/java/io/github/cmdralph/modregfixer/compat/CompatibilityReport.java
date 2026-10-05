package io.github.cmdralph.modregfixer.compat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import io.github.cmdralph.modregfixer.ModRegFixerClient;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * Result of one remote configuration: what the server provides and what had to be filtered.
 */
public final class CompatibilityReport {
	private static final String PREFIX = "[ModRegFixer] ";

	private final Instant time = Instant.now();
	private final ServerCapabilities capabilities;
	private final List<SkippedContent> skipped;

	public CompatibilityReport(ServerCapabilities capabilities, List<SkippedContent> skipped) {
		this.capabilities = capabilities;
		this.skipped = List.copyOf(skipped);
	}

	public ServerCapabilities capabilities() {
		return capabilities;
	}

	public List<SkippedContent> skipped() {
		return skipped;
	}

	public Instant time() {
		return time;
	}

	/** Namespaces (mods) whose content was filtered, mapped to the number of filtered entries. */
	public Map<String, Integer> filteredByNamespace() {
		Map<String, Integer> counts = new TreeMap<>();
		skipped.forEach(entry -> counts.merge(entry.elementNamespace(), 1, Integer::sum));
		return counts;
	}

	/** Always logged at INFO after a remote configuration: a few lines, safe for every log. */
	public void logSummary(Logger logger) {
		logger.info(PREFIX + "Joined {} (brand: {}, Fabric registry sync: {})",
				capabilities.address(), brandOrUnknown(), capabilities.fabricRegistrySync() ? "yes" : "no");

		Set<String> foreign = new TreeSet<>(capabilities.evidencedNamespaces());
		foreign.remove(Identifier.DEFAULT_NAMESPACE);
		logger.info(PREFIX + "Server data namespaces: minecraft{}", foreign.isEmpty() ? "" : ", " + String.join(", ", foreign));

		if (!capabilities.clientOnlyNamespaces().isEmpty()) {
			logger.info(PREFIX + "Client content the server does not provide (stays installed, unused here): {}",
					capabilities.clientOnlyNamespaces().entrySet().stream()
							.map(e -> e.getKey() + " (" + e.getValue() + " entries)")
							.collect(Collectors.joining(", ")));
		}

		if (skipped.isEmpty()) {
			logger.info(PREFIX + "No client content needed filtering for this server.");
		} else {
			logger.info(PREFIX + "Filtered {} item component(s) referencing content this server does not have: {}",
					skipped.size(), filteredByNamespace().entrySet().stream()
							.map(e -> ModAttribution.describe(e.getKey()) + " x" + e.getValue())
							.collect(Collectors.joining(", ")));

			if (!ModRegFixerClient.isDebug()) {
				logger.info(PREFIX + "Run /modregfixer report or enable debug=true in config/modregfixer.properties for details.");
			}
		}
	}

	/** The full report: logged when debug is on, and on demand via {@code /modregfixer report}. */
	public void logDetails(Logger logger) {
		for (String line : detailLines()) {
			logger.info(PREFIX + "{}", line);
		}
	}

	public List<String> detailLines() {
		List<String> lines = new ArrayList<>();
		lines.add("===== Server compatibility report (" + time + ") =====");
		lines.add("Server address: " + capabilities.address());
		lines.add("Server brand: " + brandOrUnknown());
		lines.add("Connection: " + (capabilities.remote() ? "remote" : "integrated (singleplayer)"));
		lines.add("Fabric registry sync: " + (capabilities.fabricRegistrySync() ? "yes (static registries are synchronized by Fabric API)" : "no (server cannot know client-only items/blocks)"));

		lines.add("Server plugin/mod channel namespaces: " + (capabilities.serverChannels().isEmpty() ? "(none announced)"
				: capabilities.serverChannels().stream().map(Identifier::getNamespace).distinct().sorted().collect(Collectors.joining(", "))));

		lines.add("Synchronized registries sent by the server:");
		capabilities.registryNamespaces().forEach((registry, namespaces) -> lines.add("  " + registry + ": "
				+ capabilities.registryElementCounts().getOrDefault(registry, 0) + " entries, namespaces " + namespaces));

		lines.add("Tag namespaces sent by the server: " + capabilities.tagNamespaces());

		if (capabilities.absentSynchronizedRegistries().isEmpty()) {
			lines.add("Client-known synchronized registries absent on the server: none");
		} else {
			lines.add("Client-known synchronized registries absent on the server: " + capabilities.absentSynchronizedRegistries());
		}

		if (capabilities.clientOnlyNamespaces().isEmpty()) {
			lines.add("Client-only content namespaces: none");
		} else {
			lines.add("Client-only content namespaces (installed, not provided by this server):");
			capabilities.clientOnlyNamespaces().forEach((namespace, count) ->
					lines.add("  " + namespace + ": " + count + " static registry entries, " + ModAttribution.describe(namespace)));
		}

		if (skipped.isEmpty()) {
			lines.add("Filtered content: none");
		} else {
			lines.add("Filtered content (" + skipped.size() + "):");

			for (SkippedContent entry : skipped) {
				lines.add("  " + entry.element().identifier() + " -> " + entry.component()
						+ " | missing " + entry.missing().describe()
						+ " | via " + entry.source().description()
						+ " | content from " + ModAttribution.describe(entry.elementNamespace())
						+ (entry.missing().namespace().equals(entry.elementNamespace()) ? ""
						: " | missing content belongs to " + ModAttribution.describe(entry.missing().namespace())));
			}
		}

		lines.add("===== end of report =====");
		return lines;
	}

	private String brandOrUnknown() {
		return capabilities.brand() != null ? capabilities.brand() : "unknown";
	}
}
