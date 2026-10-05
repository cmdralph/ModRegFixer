package io.github.cmdralph.modregfixer.compat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.cmdralph.modregfixer.ModRegFixerClient;
import io.github.cmdralph.modregfixer.config.ModRegFixerConfig;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;

/**
 * State for one remote {@code RegistryDataCollector.updateComponents} run, i.e. one time the
 * client rebuilds all default components against a remote server's registries.
 *
 * <p>Only ever touched from the thread running that build (the client's packet-processing thread);
 * it is published to other code via {@link io.github.cmdralph.modregfixer.CompatibilityManager}
 * once the build has finished.
 */
public final class ComponentBuildSession {
	private final ServerCapabilities capabilities;
	private final List<SkippedContent> skipped = new ArrayList<>();
	private final Set<String> dedupe = new LinkedHashSet<>();
	private @Nullable ResourceKey<?> currentElement;

	public ComponentBuildSession(ServerCapabilities capabilities) {
		this.capabilities = capabilities;
	}

	public ServerCapabilities capabilities() {
		return capabilities;
	}

	public List<SkippedContent> skipped() {
		return List.copyOf(skipped);
	}

	/** The element whose initializers are currently running, used to annotate failure diagnostics. */
	public @Nullable ResourceKey<?> currentElement() {
		return currentElement;
	}

	public void setCurrentElement(@Nullable ResourceKey<?> element) {
		this.currentElement = element;
	}

	public void recordSkip(ResourceKey<?> element, DataComponentType<?> type, MissingReference missing, SkippedContent.Source source) {
		recordSkip(element, componentName(type), missing, source);
	}

	public void recordSkip(ResourceKey<?> element, String component, MissingReference missing, SkippedContent.Source source) {
		// The same item/component pair can be reached twice (e.g. a Fabric event listener matching it
		// through two predicates); report it once.
		if (!dedupe.add(element + "|" + component + "|" + missing.describe())) {
			return;
		}

		SkippedContent entry = new SkippedContent(element, component, missing, source);
		skipped.add(entry);

		if (ModRegFixerConfig.get().debug()) {
			ModRegFixerClient.LOGGER.info("[ModRegFixer] Filtered {} -> {}: server is missing {} (via {})",
					element.identifier(), component, missing.describe(), source.description());
		}
	}

	private static String componentName(DataComponentType<?> type) {
		Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
		return id != null ? id.toString() : String.valueOf(type);
	}
}
