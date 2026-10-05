package io.github.cmdralph.modregfixer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.cmdralph.modregfixer.compat.CollectionContext;
import io.github.cmdralph.modregfixer.compat.CompatibilityReport;
import io.github.cmdralph.modregfixer.compat.FabricItemApiGuards;
import io.github.cmdralph.modregfixer.compat.MissingReference;
import io.github.cmdralph.modregfixer.compat.RecordingLookupProvider;
import io.github.cmdralph.modregfixer.compat.SkippedContent;
import net.fabricmc.fabric.api.item.v1.DefaultItemComponentEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongs;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.item.equipment.trim.TrimMaterials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs against the real Minecraft classes with ModRegFixer's mixins applied (fabric-loader-junit
 * boots Fabric Loader / Knot in a client environment).
 *
 * <p>The "server" is {@link VanillaRegistries#createWorldLookup()}: Minecraft's own built-in
 * registries, i.e. exactly what a vanilla server provides, with no modded content. The modded
 * content mirrors Biomes O' Plenty 26.3.0.0.13: two trim-material items and a music disc whose
 * entries exist only in the mod's datapack.
 */
class ComponentGuardsTest {
	private static final ResourceKey<TrimMaterial> BOP_GLOWWORM_SILK = ResourceKey.create(Registries.TRIM_MATERIAL, Identifier.fromNamespaceAndPath("biomesoplenty", "glowworm_silk"));
	private static final ResourceKey<TrimMaterial> BOP_ROSE_QUARTZ = ResourceKey.create(Registries.TRIM_MATERIAL, Identifier.fromNamespaceAndPath("biomesoplenty", "rose_quartz"));
	private static final ResourceKey<JukeboxSong> BOP_WANDERER = ResourceKey.create(Registries.JUKEBOX_SONG, Identifier.fromNamespaceAndPath("biomesoplenty", "wanderer"));
	private static final ResourceKey<Item> TEST_ITEM = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("biomesoplenty", "glowworm_silk"));

	private static HolderLookup.Provider vanillaServer;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		vanillaServer = VanillaRegistries.createWorldLookup();
		// Bind vanilla's own components once, like the game does on start-up.
		BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(vanillaServer).forEach(DataComponentInitializers.PendingComponents::apply);
	}

	@AfterEach
	void disconnect() {
		CompatibilityManager.onDisconnect();
	}

	// -----------------------------------------------------------------------------------------
	// Singleplayer / no remote build: must be byte-for-byte vanilla, including vanilla's crash.
	// -----------------------------------------------------------------------------------------

	@Test
	void outsideRemoteBuildBehaviourIsVanilla() {
		DataComponentInitializers.Initializer<Item> chain = chainOf(new Item.Properties().trimMaterial(BOP_GLOWWORM_SILK));

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> chain.run(DataComponentMap.builder(), vanillaServer, TEST_ITEM));
		assertEquals("Missing element ResourceKey[minecraft:trim_material / biomesoplenty:glowworm_silk]", failure.getMessage());
	}

	@Test
	void outsideRemoteBuildPresentContentIsSet() {
		DataComponentMap components = build(chainOf(new Item.Properties().trimMaterial(TrimMaterials.QUARTZ)), vanillaServer);
		assertTrue(components.has(DataComponents.PROVIDES_TRIM_MATERIAL));
	}

	// -----------------------------------------------------------------------------------------
	// Remote vanilla server: the Biomes O' Plenty case.
	// -----------------------------------------------------------------------------------------

	@Test
	void bopItemsOnVanillaServerKeepEverythingExceptUnsupportedComponents() {
		Item.Properties glowwormSilk = new Item.Properties().trimMaterial(BOP_GLOWWORM_SILK);
		Item.Properties roseQuartz = new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON).trimMaterial(BOP_ROSE_QUARTZ);
		Item.Properties wanderer = new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).jukeboxPlayable(BOP_WANDERER);

		AtomicReference<DataComponentMap> silk = new AtomicReference<>();
		AtomicReference<DataComponentMap> quartz = new AtomicReference<>();
		AtomicReference<DataComponentMap> disc = new AtomicReference<>();

		CompatibilityReport report = remoteBuild(() -> {
			silk.set(build(chainOf(glowwormSilk), vanillaServer));
			quartz.set(build(chainOf(roseQuartz), vanillaServer));
			disc.set(build(chainOf(wanderer), vanillaServer));
		});

		// Unsupported components are absent: no fabricated trim materials or songs.
		assertFalse(silk.get().has(DataComponents.PROVIDES_TRIM_MATERIAL));
		assertFalse(quartz.get().has(DataComponents.PROVIDES_TRIM_MATERIAL));
		assertFalse(disc.get().has(DataComponents.JUKEBOX_PLAYABLE));

		// Everything else about the items is intact.
		assertEquals(16, quartz.get().get(DataComponents.MAX_STACK_SIZE));
		assertEquals(Rarity.UNCOMMON, quartz.get().get(DataComponents.RARITY));
		assertEquals(1, disc.get().get(DataComponents.MAX_STACK_SIZE));
		assertEquals(Rarity.EPIC, disc.get().get(DataComponents.RARITY));

		// And every filtered piece is reported precisely.
		List<SkippedContent> skipped = report.skipped();
		assertEquals(3, skipped.size(), () -> "skipped: " + skipped);
		assertSkipped(skipped, "minecraft:provides_trim_material", MissingReference.element(BOP_GLOWWORM_SILK), SkippedContent.Source.ITEM_PROPERTIES_HOLDER);
		assertSkipped(skipped, "minecraft:provides_trim_material", MissingReference.element(BOP_ROSE_QUARTZ), SkippedContent.Source.ITEM_PROPERTIES_HOLDER);
		assertSkipped(skipped, "minecraft:jukebox_playable", MissingReference.element(BOP_WANDERER), SkippedContent.Source.ITEM_PROPERTIES_DELAYED);
	}

	@Test
	void contentTheServerHasIsNeverFiltered() {
		Item.Properties vanillaLike = new Item.Properties().trimMaterial(TrimMaterials.QUARTZ).jukeboxPlayable(JukeboxSongs.CAT);
		AtomicReference<DataComponentMap> result = new AtomicReference<>();

		CompatibilityReport report = remoteBuild(() -> result.set(build(chainOf(vanillaLike), vanillaServer)));

		assertTrue(result.get().has(DataComponents.PROVIDES_TRIM_MATERIAL));
		assertTrue(result.get().has(DataComponents.JUKEBOX_PLAYABLE));
		assertTrue(report.skipped().isEmpty(), () -> "skipped: " + report.skipped());
	}

	@Test
	void unrelatedFailuresAreNotHidden() {
		Item.Properties broken = new Item.Properties().delayedComponent(DataComponents.MAX_STACK_SIZE, context -> {
			throw new IllegalStateException("a genuine bug, not missing server content");
		});

		RuntimeException failure = assertThrows(IllegalStateException.class,
				() -> remoteBuild(() -> build(chainOf(broken), vanillaServer)));
		assertEquals("a genuine bug, not missing server content", failure.getMessage());
	}

	@Test
	void fullComponentBuildSucceedsOnVanillaServer() {
		// Same entry point Minecraft uses: DataComponentInitializers.build(...), with the real
		// runInitializers/createInitializerForRegistry code and Fabric API's mixins on it.
		ResourceKey<Item> stick = BuiltInRegistries.ITEM.getResourceKey(Items.STICK).orElseThrow();
		DataComponentInitializers initializers = new DataComponentInitializers();
		initializers.add(stick, chainOf(new Item.Properties().stacksTo(7).trimMaterial(BOP_GLOWWORM_SILK).jukeboxPlayable(BOP_WANDERER)));

		AtomicReference<DataComponentMap> stickComponents = new AtomicReference<>();
		CompatibilityReport report = remoteBuild(() -> initializers.build(vanillaServer).forEach(pending -> {
			if (pending.key().equals(Registries.ITEM)) {
				forEachUnchecked(pending, (holder, components) -> {
					if (holder.key().equals(stick)) {
						stickComponents.set(components);
					}
				});
			}
		}));

		assertNotNull(stickComponents.get());
		assertEquals(7, stickComponents.get().get(DataComponents.MAX_STACK_SIZE));
		assertFalse(stickComponents.get().has(DataComponents.PROVIDES_TRIM_MATERIAL));
		assertEquals(2, report.skipped().size());
	}

	// -----------------------------------------------------------------------------------------
	// Fabric API DefaultItemComponentEvents listeners ("optional components on vanilla content").
	// -----------------------------------------------------------------------------------------

	@Test
	void fabricListenerNeedingMissingContentIsSkipped() {
		DefaultItemComponentEvents.ModifyConsumer needsBopTrim = (builder, registries, item) ->
				builder.set(DataComponents.PROVIDES_TRIM_MATERIAL, registries.getOrThrow(BOP_ROSE_QUARTZ));
		DefaultItemComponentEvents.ModifyConsumer vanillaOnly = (builder, registries, item) ->
				builder.set(DataComponents.PROVIDES_TRIM_MATERIAL, registries.getOrThrow(TrimMaterials.QUARTZ));

		AtomicReference<Boolean> skipMissing = new AtomicReference<>();
		AtomicReference<Boolean> skipPresent = new AtomicReference<>();
		CompatibilityReport report = remoteBuild(() -> {
			skipMissing.set(FabricItemApiGuards.shouldSkip(needsBopTrim, vanillaServer, Items.AMETHYST_SHARD));
			skipPresent.set(FabricItemApiGuards.shouldSkip(vanillaOnly, vanillaServer, Items.AMETHYST_SHARD));
		});

		assertTrue(skipMissing.get());
		assertFalse(skipPresent.get());
		assertEquals(1, report.skipped().size());
		assertEquals(SkippedContent.Source.FABRIC_DEFAULT_ITEM_COMPONENT_EVENT, report.skipped().getFirst().source());
		// The probe never touched the item itself.
		assertFalse(Items.AMETHYST_SHARD.components().has(DataComponents.PROVIDES_TRIM_MATERIAL));
	}

	@Test
	void fabricListenersAreUntouchedOutsideRemoteBuild() {
		DefaultItemComponentEvents.ModifyConsumer needsBopTrim = (builder, registries, item) ->
				builder.set(DataComponents.PROVIDES_TRIM_MATERIAL, registries.getOrThrow(BOP_ROSE_QUARTZ));
		assertFalse(FabricItemApiGuards.shouldSkip(needsBopTrim, vanillaServer, Items.AMETHYST_SHARD));
	}

	// -----------------------------------------------------------------------------------------
	// The probing view never fabricates or substitutes anything.
	// -----------------------------------------------------------------------------------------

	@Test
	void recordingProviderReturnsTheServersOwnHoldersAndRecordsMisses() {
		RecordingLookupProvider recorder = new RecordingLookupProvider(vanillaServer);

		Holder.Reference<TrimMaterial> viaRecorder = recorder.getOrThrow(TrimMaterials.QUARTZ);
		Holder.Reference<TrimMaterial> direct = vanillaServer.getOrThrow(TrimMaterials.QUARTZ);
		assertSame(direct, viaRecorder);
		assertTrue(recorder.missing().isEmpty());

		assertThrows(IllegalStateException.class, () -> recorder.getOrThrow(BOP_GLOWWORM_SILK));
		assertEquals(List.of(MissingReference.element(BOP_GLOWWORM_SILK)), recorder.missing());
	}

	// -----------------------------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------------------------

	/** Runs {@code action} the way Minecraft runs a remote server's component update. */
	private static CompatibilityReport remoteBuild(Runnable action) {
		RegistryAccess.Frozen registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		CollectionContext remote = new CollectionContext(true, Map.of(), Set.of());

		CompatibilityManager.runCollection(remote, () -> {
			CompatibilityManager.runComponentUpdate(registries, true, action);
			return null;
		});

		return CompatibilityManager.currentReport().orElseThrow();
	}

	private static DataComponentMap build(DataComponentInitializers.Initializer<Item> chain, HolderLookup.Provider registries) {
		DataComponentMap.Builder builder = DataComponentMap.builder();
		chain.run(builder, registries, TEST_ITEM);
		return builder.build();
	}

	/** The initializer chain {@code Item.Properties} has accumulated so far (the field Item's constructor consumes). */
	@SuppressWarnings("unchecked")
	private static DataComponentInitializers.Initializer<Item> chainOf(Item.Properties properties) {
		try {
			Field field = Item.Properties.class.getDeclaredField("componentInitializer");
			field.setAccessible(true);
			return (DataComponentInitializers.Initializer<Item>) field.get(properties);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Item.Properties.componentInitializer not found", e);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void forEachUnchecked(DataComponentInitializers.PendingComponents<?> pending, java.util.function.BiConsumer<Holder.Reference<?>, DataComponentMap> consumer) {
		((DataComponentInitializers.PendingComponents) pending).forEach((java.util.function.BiConsumer) consumer);
	}

	private static void assertSkipped(List<SkippedContent> skipped, String component, MissingReference missing, SkippedContent.Source source) {
		assertTrue(skipped.stream().anyMatch(entry -> entry.component().equals(component) && entry.missing().equals(missing) && entry.source() == source),
				() -> "expected " + component + " / " + missing + " / " + source + " in " + skipped);
	}
}
