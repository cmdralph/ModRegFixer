package io.github.cmdralph.modregfixer.compat;

import io.github.cmdralph.modregfixer.CompatibilityManager;
import net.fabricmc.fabric.api.item.v1.DefaultItemComponentEvents;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Guard for Fabric API's {@link DefaultItemComponentEvents#MODIFY}, the API mods use to add or
 * change components on <em>other</em> mods' or vanilla items ("optional components on vanilla
 * content").
 *
 * <p>Fabric runs each listener as: copy the item's current components into a fresh builder, let the
 * listener modify it, then bind the result. If a listener needs content the server lacks, it
 * throws halfway and the connection fails.
 *
 * <p>The guard probes the listener on a <em>separate</em> scratch copy, against a recording view of
 * the server's registries. If and only if the probe fails because the server lacks something the
 * listener asked for, the real call is skipped. Fabric then binds its untouched builder, i.e. an
 * exact copy of the item's existing components: the item stays exactly as it was. In every
 * other case the listener runs normally against the real registries.
 */
public final class FabricItemApiGuards {
	private FabricItemApiGuards() {
	}

	/** @return {@code true} if the real listener call must be skipped for this item */
	public static boolean shouldSkip(DefaultItemComponentEvents.ModifyConsumer consumer, HolderLookup.Provider registries, Item item) {
		ComponentBuildSession session = CompatibilityManager.activeBuild();

		if (session == null) {
			return false;
		}

		RecordingLookupProvider recorder = new RecordingLookupProvider(registries);
		DataComponentMap.Builder scratch = DataComponentMap.builder().addAll(item.components());

		try {
			consumer.modify(scratch, recorder, item);
			return false;
		} catch (RuntimeException probeFailure) {
			MissingReference missing = ComponentGuards.attribute(probeFailure, recorder);

			if (missing == null) {
				// Not caused by missing server content: let the real call fail exactly like vanilla.
				return false;
			}

			session.recordSkip(BuiltInRegistries.ITEM.getResourceKey(item).orElseThrow(),
					"listener " + consumer.getClass().getName(), missing,
					SkippedContent.Source.FABRIC_DEFAULT_ITEM_COMPONENT_EVENT);
			return true;
		}
	}
}
