package io.github.cmdralph.modregfixer.compat;

import java.util.NoSuchElementException;

import io.github.cmdralph.modregfixer.CompatibilityManager;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Per-component guards installed around the delayed initializers that {@code Item.Properties}
 * registers.
 *
 * <h2>Why per component</h2>
 * In 26.x every item contributes <em>one</em> {@link DataComponentInitializers.Initializer} that is a
 * chain of {@code andThen(...)} links, one link per {@code Item.Properties} call. Once the chain is
 * built it cannot be split apart again, and a failure in any link aborts the whole
 * {@code DataComponentInitializers.build(...)}, and with it the connection. The mixin
 * {@code ItemPropertiesMixin} therefore wraps each <em>individual</em> delayed link at the moment
 * it is added, so a single unsupported component can be left out while the item keeps its name,
 * model, stack size and every other component.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *     <li>Outside a remote component build ({@link CompatibilityManager#activeBuild()} is
 *     {@code null}), i.e. singleplayer, the integrated server, dedicated-server-style reloads and
 *     game start-up, every guard simply calls the vanilla link. Nothing changes.</li>
 *     <li>Inside a remote build, a link is skipped <em>only</em> when it would have looked up a
 *     registry element, tag or registry that the server's registries do not contain, which is
 *     exactly the situation in which vanilla throws {@code IllegalStateException: Missing element}.
 *     In every other situation the vanilla link runs, unchanged, against the real provider.</li>
 * </ul>
 */
public final class ComponentGuards {
	private ComponentGuards() {
	}

	/**
	 * Guard for {@code Item.Properties#delayedHolderComponent(type, valueKey)}, which vanilla
	 * implements as {@code components.set(type, context.getOrThrow(valueKey))}.
	 *
	 * <p>Here we know the exact key up front, so no probing and no exception handling is needed:
	 * we ask the provider whether the element exists using the non-throwing
	 * {@code HolderGetter.Provider#get(ResourceKey)}, which performs the very same lookup that
	 * {@code getOrThrow} performs.
	 */
	public static DataComponentInitializers.Initializer<Item> guardHolderComponent(
			DataComponentType<?> type,
			ResourceKey<?> valueKey,
			DataComponentInitializers.Initializer<Item> vanilla) {
		return (components, context, key) -> {
			ComponentBuildSession session = CompatibilityManager.activeBuild();

			if (session == null || isPresent(context, valueKey)) {
				vanilla.run(components, context, key);
				return;
			}

			// Leave the builder untouched: the item simply does not get this component on this server.
			session.recordSkip(key, type, missingFor(context, valueKey), SkippedContent.Source.ITEM_PROPERTIES_HOLDER);
		};
	}

	/**
	 * Guard for {@code Item.Properties#delayedComponent(type, initializer)}, whose initializer is an
	 * arbitrary lambda (e.g. {@code context -> new JukeboxPlayable(context.getOrThrow(song))}).
	 *
	 * <p>Because the lambda is opaque, it is first run as a <em>probe</em>: the vanilla link runs into
	 * a throw-away builder, against a {@link RecordingLookupProvider} that returns the server's real
	 * holders but remembers any lookup that came back empty.
	 * <ul>
	 *     <li>Probe succeeded: run the vanilla link for real. The result is identical to vanilla.</li>
	 *     <li>Probe failed <em>and</em> the recorder saw the server lacked something the link asked
	 *     for: skip the link. This is precisely the case where vanilla would have crashed. The
	 *     real builder was never touched (the probe used its own builder), so state stays
	 *     consistent.</li>
	 *     <li>Probe failed for any other reason: run the vanilla link for real, which reproduces
	 *     vanilla's own exception. Unrelated bugs are never hidden.</li>
	 * </ul>
	 */
	public static DataComponentInitializers.Initializer<Item> guardDelayedComponent(
			DataComponentType<?> type,
			DataComponentInitializers.Initializer<Item> vanilla) {
		return (components, context, key) -> {
			ComponentBuildSession session = CompatibilityManager.activeBuild();

			if (session != null) {
				MissingReference missing = probe(vanilla, context, key);

				if (missing != null) {
					session.recordSkip(key, type, missing, SkippedContent.Source.ITEM_PROPERTIES_DELAYED);
					return;
				}
			}

			vanilla.run(components, context, key);
		};
	}

	/**
	 * Runs {@code initializer} into a scratch builder against a recording view of {@code context}.
	 *
	 * @return the missing content that made the initializer fail, or {@code null} if it succeeded or
	 * failed for a reason that cannot be attributed to missing server content
	 */
	static <T> @Nullable MissingReference probe(
			DataComponentInitializers.Initializer<T> initializer,
			HolderLookup.Provider context,
			ResourceKey<T> key) {
		RecordingLookupProvider recorder = new RecordingLookupProvider(context);

		try {
			initializer.run(DataComponentMap.builder(), recorder, key);
			return null;
		} catch (RuntimeException probeFailure) {
			return attribute(probeFailure, recorder);
		}
	}

	/**
	 * Decides whether a probe failure was caused by missing server content. Both conditions must
	 * hold: the recorder observed an empty lookup, and the failure is of the kind that vanilla
	 * raises for one ({@code getOrThrow}/{@code lookupOrThrow} throw {@link IllegalStateException};
	 * {@code Optional.orElseThrow()} throws {@link NoSuchElementException}).
	 */
	static @Nullable MissingReference attribute(RuntimeException failure, RecordingLookupProvider recorder) {
		MissingReference missing = recorder.firstMissing();

		if (missing == null) {
			return null;
		}

		if (failure instanceof IllegalStateException || failure instanceof NoSuchElementException) {
			return missing;
		}

		return null;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static boolean isPresent(HolderLookup.Provider context, ResourceKey<?> valueKey) {
		return context.get((ResourceKey) valueKey).isPresent();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static MissingReference missingFor(HolderLookup.Provider context, ResourceKey<?> valueKey) {
		ResourceKey registryKey = valueKey.registryKey();

		if (context.lookup(registryKey).isEmpty()) {
			return MissingReference.registry(registryKey);
		}

		return MissingReference.element(valueKey);
	}
}
