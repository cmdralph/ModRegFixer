package io.github.cmdralph.modregfixer.mixin;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.cmdralph.modregfixer.CompatibilityManager;
import io.github.cmdralph.modregfixer.compat.CollectionContext;
import net.minecraft.client.multiplayer.RegistryDataCollector;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.tags.TagNetworkSerialization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The interception point.
 *
 * <pre>
 * ClientConfigurationPacketListenerImpl.handleConfigurationFinished
 *   └─ RegistryDataCollector.collectGameRegistries(knownPacks, original, isMemoryConnection)   ← wrapped: scope
 *        ├─ loadNewElementsAndTags(...)        server registries are loaded and frozen
 *        └─ updateComponents(frozen, !isMemoryConnection)                                    ← wrapped: guard
 *             └─ BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(frozen)
 *                  └─ every item's Item.Properties initializer chain    → getOrThrow → crash (vanilla)
 * </pre>
 *
 * {@code updateComponents} is the earliest point at which the server's registries are complete
 * (so "is X supported?" has a definite answer) and the latest point before any item component is
 * built. On remote connections vanilla passes {@code includeSharedRegistries = true} here and
 * rebuilds the components of <em>every</em> registry, including the client's static item registry,
 * against the server's data. That is why client-only content is evaluated at all.
 */
@Mixin(RegistryDataCollector.class)
public abstract class RegistryDataCollectorMixin {
	@Unique
	private final Map<ResourceKey<? extends Registry<?>>, Set<Identifier>> modregfixer$sentElements = new LinkedHashMap<>();
	@Unique
	private final Set<ResourceKey<? extends Registry<?>>> modregfixer$sentTagRegistries = new LinkedHashSet<>();

	/** Records which registry entries the server sent (ids only; the data itself is untouched). */
	@Inject(method = "appendContents", at = @At("HEAD"))
	private void modregfixer$recordContents(ResourceKey<? extends Registry<?>> registry, List<RegistrySynchronization.PackedRegistryEntry> elementData, CallbackInfo ci) {
		Set<Identifier> ids = modregfixer$sentElements.computeIfAbsent(registry, key -> new HashSet<>());

		for (RegistrySynchronization.PackedRegistryEntry entry : elementData) {
			ids.add(entry.id());
		}
	}

	/** Records which registries the server sent tags for. */
	@Inject(method = "appendTags", at = @At("HEAD"))
	private void modregfixer$recordTags(Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> data, CallbackInfo ci) {
		modregfixer$sentTagRegistries.addAll(data.keySet());
	}

	@WrapMethod(method = "collectGameRegistries")
	private RegistryAccess.Frozen modregfixer$scopeCollection(
			ResourceProvider knownDataSource,
			RegistryAccess.Frozen originalRegistries,
			boolean tagsAndComponentsForSynchronizedRegistriesOnly,
			Operation<RegistryAccess.Frozen> original) {
		// Vanilla passes connection.isMemoryConnection() as the boolean: true only for the integrated server.
		CollectionContext context = new CollectionContext(
				!tagsAndComponentsForSynchronizedRegistriesOnly,
				Map.copyOf(modregfixer$sentElements),
				Set.copyOf(modregfixer$sentTagRegistries));

		return CompatibilityManager.runCollection(context,
				() -> original.call(knownDataSource, originalRegistries, tagsAndComponentsForSynchronizedRegistriesOnly));
	}

	@WrapMethod(method = "updateComponents")
	private static void modregfixer$guardComponentUpdate(
			RegistryAccess.Frozen frozenRegistries,
			boolean includeSharedRegistries,
			Operation<Void> original) {
		CompatibilityManager.runComponentUpdate(frozenRegistries, includeSharedRegistries,
				() -> original.call(frozenRegistries, includeSharedRegistries));
	}
}
