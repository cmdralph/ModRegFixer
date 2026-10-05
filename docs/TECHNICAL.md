# ModRegFixer: technical design

This document explains exactly why a modded 26.x client crashes on vanilla-compatible servers, where
ModRegFixer intervenes, and why that intervention is safe. Everything here was verified against the
decompiled Minecraft **26.3** client (protocol 777), Fabric API **0.161.0+26.3**, Fabric Loader
**0.19.5** and Biomes O' Plenty's **26.3** source. 26.1 and 26.2 were diffed for the same classes.

---

## 1. The failure mechanism

### 1.1 Item components are no longer built in the `Item` constructor

Since 26.1, an item's default data components are not fixed when the item is created. Instead
`Item.Properties` accumulates **one `DataComponentInitializers.Initializer<Item>` per item**, a chain of
`andThen(...)` links, one per builder call:

```java
// net.minecraft.world.item.Item.Properties (26.3)
private DataComponentInitializers.Initializer<Item> componentInitializer =
        (builder, ctx, key) -> builder.addAll(DataComponents.COMMON_ITEM_COMPONENTS);

public <T> Properties component(DataComponentType<T> type, T value) {           // fixed value
    this.componentInitializer = this.componentInitializer.add(type, value);
    return this;
}

public <T> Properties delayedComponent(DataComponentType<T> type,
                                       DataComponentInitializers.SingleComponentInitializer<T> init) {
    this.componentInitializer = this.componentInitializer.andThen(init.asInitializer(type));
    return this;
}

public <T> Properties delayedHolderComponent(DataComponentType<Holder<T>> type, ResourceKey<T> valueKey) {
    this.componentInitializer = this.componentInitializer.andThen(
            (components, context, key) -> components.set(type, context.getOrThrow(valueKey)));   // ← throws
    return this;
}

public Properties trimMaterial(ResourceKey<TrimMaterial> material) {
    return this.delayedHolderComponent(DataComponents.PROVIDES_TRIM_MATERIAL, material);
}
```

The `Item` constructor registers that chain globally:

```java
DataComponentInitializers.Initializer<Item> componentInitializer =
        properties.finalizeInitializer(Component.translatable(this.descriptionId), properties.effectiveModel());
BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.add(properties.itemIdOrThrow(), componentInitializer);
```

"Delayed" components are the ones whose value is a **holder from a datapack (synchronized) registry**,
such as trim materials, jukebox songs, damage types, block transformers, pot patterns and tags. Their value
depends on *which* registries are active, so they are re-evaluated every time the registries change.

### 1.2 Biomes O' Plenty's three affected items

From `biomesoplenty/init/ModItems.java` (26.3 branch):

| Item | Builder call | Path | Needs on the server |
|---|---|---|---|
| `biomesoplenty:glowworm_silk` | `.trimMaterial(ModTrimMaterials.GLOWWORM_SILK)` | `delayedHolderComponent` | `minecraft:trim_material / biomesoplenty:glowworm_silk` |
| `biomesoplenty:rose_quartz_chunk` | `.trimMaterial(ModTrimMaterials.ROSE_QUARTZ)` | `delayedHolderComponent` | `minecraft:trim_material / biomesoplenty:rose_quartz` |
| `biomesoplenty:music_disc_wanderer` | `.jukeboxPlayable(ModJukeboxSongs.WANDERER)` | `delayedComponent` | `minecraft:jukebox_song / biomesoplenty:wanderer` |

Those registry entries come from BOP's **datapack** (`data/biomesoplenty/trim_material/*.json`,
`data/biomesoplenty/jukebox_song/wanderer.json`). In singleplayer the integrated server loads that
datapack. A vanilla, Paper or Folia server never has it.

Only `glowworm_silk` shows in your crash because the first failure aborts the build. **A fix
for `glowworm_silk` alone would crash next on `rose_quartz_chunk` or the music disc.** That's why
ModRegFixer is generic.

### 1.3 Why the client evaluates these at all on a remote server

```java
// ClientConfigurationPacketListenerImpl.handleConfigurationFinished (26.3)
RegistryAccess.Frozen registries = runWithResources(knownPacks ->
        registryDataCollector.collectGameRegistries(knownPacks, receivedRegistries,
                                                    connection.isMemoryConnection()));   // false on a real server

// RegistryDataCollector.collectGameRegistries
RegistryAccess.Frozen frozenRegistries = registries.freeze();
updateComponents(frozenRegistries, !tagsAndComponentsForSynchronizedRegistriesOnly);    // true on a real server

// RegistryDataCollector.updateComponents
BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(frozenRegistries).forEach(pending -> {
    if (includeSharedRegistries || RegistrySynchronization.isNetworkable(pending.key())) {
        pending.apply();
    }
});
```

On a remote connection `includeSharedRegistries` is `true`. So the client rebuilds the components of
**every registry, including its own static `ITEM` registry, which contains BOP's items**, against the
**server's** registries. `DataComponentInitializers.build` → `runInitializers` → BOP's chain →
`context.getOrThrow(biomesoplenty:glowworm_silk)` → `HolderGetter.Provider.getOrThrow`:

```java
default <T> Holder.Reference<T> getOrThrow(ResourceKey<T> id) {
    return lookup(id.registryKey()).flatMap(l -> l.get(id))
            .orElseThrow(() -> new IllegalStateException("Missing element " + id));
}
```

The exception escapes `build()` before anything has been applied, and the connection is torn down.
That matches your stack trace frame for frame.

### 1.4 Why singleplayer is fine (and stays fine)

* Integrated server = memory connection → `includeSharedRegistries = false` → the client does not
  re-apply static-registry components.
* The integrated server builds and applies all components itself in
  `ReloadableServerResources.loadResources` / `updateComponentsAndStaticRegistryTags`, on its own
  threads, with the full BOP datapack loaded. This happens on **every world load**, so after
  visiting a vanilla server, BOP's items get all their components back the next time you open a
  world.

### 1.5 Why ModernRegSyncFix doesn't help

Registry *synchronization* succeeded. The server's registries were received and frozen correctly.
The failure happens one step later, when the client's own item definitions are evaluated against those
registries. No registry-sync fix can change that, because the problem is not the sync.

---

## 2. The interception point

```
handleConfigurationFinished
 └─ RegistryDataCollector.collectGameRegistries(…, isMemoryConnection)   ◄─ @WrapMethod: scope (what the server sent, remote?)
     ├─ loadNewElementsAndTags(…)                                         server registries loaded + frozen
     └─ updateComponents(frozen, includeShared)                           ◄─ @WrapMethod: analyse server, open build session
         └─ DATA_COMPONENT_INITIALIZERS.build(frozen)
             └─ runInitializers                                           ◄─ @WrapOperation (diagnostics: current element)
                 └─ item chain: … → [guarded link] → [guarded link] → …   ◄─ links wrapped at Item.Properties time
             └─ PendingComponents.apply → Fabric DefaultItemComponentEvents.MODIFY
                                                └─ each listener call      ◄─ @WrapOperation (optional, @Pseudo)
```

**`RegistryDataCollector.updateComponents(RegistryAccess.Frozen, boolean)` is the earliest safe point.**
Before it, the server's registries are not complete, so "is X supported?" has no definite answer.
After it, the exception has already happened.

But `updateComponents` alone can't filter *one* component. By then each item's initializer is a
single opaque lambda chain. That's why the second half of the design hooks in where the chain is built:

**`Item.Properties.delayedHolderComponent` / `delayedComponent`**: a `@WrapOperation` on the
`Initializer.andThen(link)` call replaces just the new `link` with a guarded version. The chain, the
field and the return value stay vanilla's. Every vanilla helper that depends on server registries
(`trimMaterial`, `jukeboxPlayable`, `fireResistant`, `potPattern`, `spear`, `axe`, `hoe`, `shovel`,
`loweredMobVisibility`) routes through these two methods, as does any mod calling them directly.

### 2.1 What a guarded link does

Outside a remote build session (singleplayer, integrated server, start-up, tests), **every guard calls the
vanilla link directly**. Inside one:

**`delayedHolderComponent(type, valueKey)`** has no exception handling at all:

```java
if (context.get(valueKey).isPresent())   // same lookup getOrThrow performs, non-throwing variant
    vanilla.run(components, context, key);
else
    record skip;                         // builder untouched: the item just lacks this component here
```

**`delayedComponent(type, initializer)`** wraps an opaque lambda (e.g. `ctx -> new JukeboxPlayable(ctx.getOrThrow(song))`), so it is probed:

1. Run the **vanilla link** into a *throw-away* builder, against a `RecordingLookupProvider`: a read-only view
   of the server's registries that returns the server's real holders but records every lookup that came back empty.
2. Probe succeeded → run the vanilla link for real against the real provider. Result is identical to vanilla.
3. Probe failed **and** the recorder saw a missing element/tag/registry **and** the failure is
   `IllegalStateException` / `NoSuchElementException` (what `getOrThrow` / `orElseThrow` raise) →
   skip. This is exactly the case where vanilla would have crashed.
4. Probe failed any other way → run the vanilla link for real, which reproduces vanilla's own
   exception. **Unrelated bugs are never hidden.**

#### Why the probe's `catch` is safe (not "catch and ignore")

* The probe writes only to its own scratch builder, which is discarded. The real builder is never
  touched by a failed probe, so there is no partially-applied state.
* Item initializers only write to the builder they are given (vanilla's are pure lambdas).
* An exception is swallowed only when we have positive evidence (from the recorder) that the
  server lacks content the link asked for. In every other case the real vanilla code runs and
  behaves exactly as without the mod.
* The value that ends up on the item is always produced by vanilla code against the real provider.
  The recording view never leaks into game state.

### 2.2 Fabric API `DefaultItemComponentEvents.MODIFY` (optional components on other mods' items)

Fabric runs these listeners from `PendingComponents.apply` for the `ITEM` registry, inside our session:

```java
DataComponentMap.Builder builder = DataComponentMap.builder().addAll(item.components());
builderConsumer.modify(builder, registryLookup, item);          // ← guarded
item.builtInRegistryHolder().bindComponents(builder.build());
```

The guard probes the listener on a *separate* copy. If it needs missing server content, the real call is
skipped. Fabric then binds its untouched copy, which is exactly the item's existing components
(`bindComponents` is a plain field write). This hook targets a Fabric internal class, so it lives in an
optional mixin config (`required: false`, `require = 0`, `@Pseudo`). If Fabric ever changes that class,
the hook silently disappears and nothing else is affected.

### 2.3 What is deliberately *not* done

* **No placeholder / fabricated registry entries.** A missing trim material stays missing.
* **No registry IDs are changed**, no registry is modified, nothing is removed from any registry.
* **No exception is swallowed at `build()` level.** If some content was registered outside the paths above
  (e.g. a mod calling `DATA_COMPONENT_INITIALIZERS.add` with its own lambda), it can't be filtered without
  risking inconsistent item data. ModRegFixer then logs a precise diagnostic (item, owning mod, missing key,
  full server report) and rethrows the original exception unchanged. You get the same outcome as without the mod,
  just with a clear explanation.

---

## 3. Server capability detection

All of it is computed at `updateComponents`, while Fabric's configuration-phase networking is still active.

| Signal | Source | Used for |
|---|---|---|
| Registry entries sent, per registry | `@Inject` on `RegistryDataCollector.appendContents` (ids of `PackedRegistryEntry`) | **authoritative**: namespaces present, diagnostics |
| Registries with tags sent, tag names & members | `@Inject` on `appendTags` + the frozen registries | namespace evidence |
| The exact missing elements | the guards themselves (presence check / recorder) | **authoritative**: filtering |
| Fabric registry sync | `ClientConfigurationNetworking.getSendable()` contains `fabric:registry/sync/complete` | creative filter (Fabric servers keep static registries in sync) |
| Plugin/mod channels | `ClientConfigurationNetworking.getSendable()` | diagnostics only |
| Server brand, address | `ClientCommonPacketListenerImpl.serverBrand` / `serverData` (accessor mixin) | diagnostics only |
| Memory vs remote connection | the boolean vanilla passes to `collectGameRegistries` | on/off switch |

**Registry presence decides everything.** The mod list is never assumed; vanilla/Paper/Folia/Velocity
don't send one. A server *with* BOP sends BOP's trim materials and jukebox song, nothing is missing,
and nothing is filtered. That holds whether it's a Fabric server, a LAN world, or a proxy whose backend has BOP.

### 3.1 Content categories

| Category | Example | What ModRegFixer does |
|---|---|---|
| 1. Client-only mods | Sodium, Iris, AppleSkin, Xaero's, JEI, ModernFix… | Nothing. They register no server-dependent component initializers, so the guards are never triggered. |
| 2. Mods adding registries/content | BOP blocks, items, biomes | Items/blocks stay registered client-side (the server never sends them). Item components that need the server's datapack content are left out for this connection. |
| 3. Optional components on vanilla content | Fabric `DefaultItemComponentEvents` listeners | Listener skipped for an item only if it needs missing server content. |
| 4. Content safe to keep client-side | anything referencing only vanilla content | Untouched (present ⇒ vanilla path). |
| 5. Content requiring server support | `biomesoplenty:glowworm_silk` trim material | Filtered on that server; restored in singleplayer. |

### 3.2 Creative menu (optional, default on)

On servers **without** Fabric registry sync, the item registry is vanilla's. Picking a modded item in creative
would send an item id the server doesn't know. Items whose namespace has *no trace at all* in the server's
data are hidden from creative tabs and search, using Fabric API's
`CreativeModeTabEvents.MODIFY_OUTPUT_ALL` in a phase after all other listeners. Nothing is hidden in singleplayer
or on Fabric servers, and nothing is removed from any registry.

---

## 4. Thread confinement

The build session is a `ThreadLocal` set only for the duration of the client's `updateComponents` call
on the packet-processing thread, and always restored in `finally`. The integrated server builds
components on `Util.backgroundExecutor()` threads and never sees it. A Velocity backend switch
(play → configuration → play) creates a fresh `RegistryDataCollector` and a fresh session.

---

## 5. Versions, mappings and dependencies

| | |
|---|---|
| Minecraft | **26.3** (primary). Also built for 26.2 and 26.1 (26.1–26.1.2) |
| Mappings | **none**. Minecraft 26.x ships unobfuscated, so the code uses Mojang's own names and Loom does no remapping |
| Fabric Loader | 0.19.5 |
| Fabric Loom | 1.18-SNAPSHOT (`net.fabricmc.fabric-loom`, the non-remapping plugin) |
| Fabric API | 0.161.0+26.3 · 0.161.0+26.2 · 0.145.1+26.1 |
| Gradle | 9.7.1 (wrapper) |
| Java | 25 (required by Minecraft 26.x) |
| Mixin features | SpongePowered Mixin + MixinExtras (`@WrapMethod`, `@WrapOperation`, `@Local`), both bundled with Fabric Loader |
| Access wideners / class tweakers | **none needed**. Protected fields are read through an `@Accessor` mixin |

Fabric API modules used: `fabric-networking-api-v1` (configuration events, channel list),
`fabric-command-api-v2` (client command), `fabric-creative-tab-api-v1` (creative filter),
`fabric-item-api-v1` (optional guard target), `fabric-api-base` (event phases).

### 5.1 Pre-26.1 (1.21.x)

`DataComponentInitializers` doesn't exist there; item components are fixed in the `Item` constructor,
and trim materials are referenced lazily (`EitherHolder`). This specific crash can't happen there, so
there is nothing for ModRegFixer to do, and it isn't built for those versions.
