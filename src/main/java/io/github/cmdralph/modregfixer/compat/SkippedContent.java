package io.github.cmdralph.modregfixer.compat;

import net.minecraft.resources.ResourceKey;

/**
 * One piece of client content that was filtered for the current connection because it
 * references something the server does not provide.
 *
 * @param element   the registry element whose default components were being built (usually an item)
 * @param component the data component (or Fabric event) that was left out
 * @param missing   what the server is missing
 * @param source    which code path registered the filtered content
 */
public record SkippedContent(ResourceKey<?> element, String component, MissingReference missing, Source source) {
	public enum Source {
		/** {@code Item.Properties#delayedHolderComponent}, e.g. {@code trimMaterial(...)}, spears' damage type. */
		ITEM_PROPERTIES_HOLDER("Item.Properties.delayedHolderComponent"),
		/** {@code Item.Properties#delayedComponent}, e.g. {@code jukeboxPlayable(...)}, {@code fireResistant()}. */
		ITEM_PROPERTIES_DELAYED("Item.Properties.delayedComponent"),
		/** A Fabric API {@code DefaultItemComponentEvents.MODIFY} listener. */
		FABRIC_DEFAULT_ITEM_COMPONENT_EVENT("Fabric DefaultItemComponentEvents.MODIFY");

		private final String description;

		Source(String description) {
			this.description = description;
		}

		public String description() {
			return description;
		}
	}

	public String elementNamespace() {
		return element.identifier().getNamespace();
	}
}
