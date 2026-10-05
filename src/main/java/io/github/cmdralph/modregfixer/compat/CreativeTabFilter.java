package io.github.cmdralph.modregfixer.compat;

import io.github.cmdralph.modregfixer.CompatibilityManager;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.resources.Identifier;

/**
 * Hides creative-menu items that the current server cannot know about.
 *
 * <p>Only active on servers <em>without</em> Fabric registry sync (vanilla, Paper, Folia, most
 * proxies): their item registry is vanilla's, so picking a modded item in creative mode would send
 * an item id the server does not have. Items are hidden only when the server's data shows no trace
 * of their namespace at all; in singleplayer and on Fabric servers nothing is hidden.
 *
 * <p>Uses Fabric API's {@link CreativeModeTabEvents#MODIFY_OUTPUT_ALL} in a phase that runs after
 * every other listener, so items added by other mods are seen too. Nothing is removed from any
 * registry; the menu is rebuilt with all items when the player returns to singleplayer.
 */
public final class CreativeTabFilter {
	private static final Identifier PHASE = Identifier.fromNamespaceAndPath("modregfixer", "after_all");

	private CreativeTabFilter() {
	}

	public static void register() {
		CreativeModeTabEvents.MODIFY_OUTPUT_ALL.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
		CreativeModeTabEvents.MODIFY_OUTPUT_ALL.register(PHASE, (tab, output) -> {
			if (!CompatibilityManager.isMultiplayer()) {
				return;
			}

			output.getDisplayStacks().removeIf(CompatibilityManager::shouldHideFromCreative);
			output.getSearchTabStacks().removeIf(CompatibilityManager::shouldHideFromCreative);
		});
	}
}
