package io.github.cmdralph.modregfixer.compat;

import java.util.Map;
import java.util.Set;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * What the server sent during the configuration phase, captured from
 * {@code RegistryDataCollector.appendContents/appendTags} before Minecraft processes it.
 *
 * @param remote            {@code false} for the in-memory connection to the integrated (singleplayer/LAN host) server
 * @param sentElements      every registry the server sent contents for, with the ids of the entries it sent
 * @param sentTagRegistries every registry the server sent tags for
 */
public record CollectionContext(
		boolean remote,
		Map<ResourceKey<? extends Registry<?>>, Set<Identifier>> sentElements,
		Set<ResourceKey<? extends Registry<?>>> sentTagRegistries
) {
}
