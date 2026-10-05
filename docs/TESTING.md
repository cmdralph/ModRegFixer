# Testing ModRegFixer

## 1. Automated tests (run on every push)

`./gradlew build` runs `src/test/java/.../ComponentGuardsTest.java` through **fabric-loader-junit**.
That boots Fabric Loader in a client environment, so the tests run against the **real Minecraft classes
with ModRegFixer's mixins (and Fabric API's) applied**. The "server" in the tests is
`VanillaRegistries.createWorldLookup()`, Minecraft's own built-in registries, i.e. exactly what a vanilla
server provides.

| Test | Proves |
|---|---|
| `outsideRemoteBuildBehaviourIsVanilla` | Without a remote session (singleplayer), behaviour is vanilla, including vanilla's exact `Missing element ResourceKey[minecraft:trim_material / biomesoplenty:glowworm_silk]` |
| `outsideRemoteBuildPresentContentIsSet` | Normal content still works outside a session |
| `bopItemsOnVanillaServerKeepEverythingExceptUnsupportedComponents` | BOP's `glowworm_silk`, `rose_quartz_chunk` and `music_disc_wanderer` definitions build on a vanilla server: only the trim/jukebox components are missing, stack size/rarity intact, all three reported |
| `contentTheServerHasIsNeverFiltered` | Vanilla trim materials and songs are untouched |
| `unrelatedFailuresAreNotHidden` | A genuine bug in an initializer still throws |
| `fullComponentBuildSucceedsOnVanillaServer` | The full `DataComponentInitializers.build(...)` path (the one in your stack trace) succeeds |
| `fabricListenerNeedingMissingContentIsSkipped` / `fabricListenersAreUntouchedOutsideRemoteBuild` | Fabric `DefaultItemComponentEvents` guard |
| `recordingProviderReturnsTheServersOwnHoldersAndRecordsMisses` | The probing view returns the server's own holder objects; nothing is fabricated |

CI builds and tests Minecraft 26.1, 26.2 and 26.3 (`.github/workflows/build.yml`).

## 2. In-game test with Biomes O' Plenty 26.3.0.0.13

### Setup

One instance, one mods folder:

```
Minecraft 26.3, Fabric Loader 0.19.5
mods/
  fabric-api-0.161.0+26.3.jar
  BiomesOPlenty-fabric-26.3.0.0.13.jar
  GlitchCore-fabric-26.3.0.0.3.jar
  TerraBlender-fabric-26.3-*.jar          (BOP dependency)
  modregfixer-1.0.0+26.3.jar
  (your usual client mods: Sodium, Iris, …)
```

Optional, for the most detail: launch with JVM argument `-Dmodregfixer.debug=true`, or set
`debug=true` in `config/modregfixer.properties`.

### A. Baseline (proves the crash, without the fix)

1. Temporarily remove `modregfixer-*.jar`, launch, join the vanilla/Paper/Folia server.
2. Expected: disconnect/crash with
   `Missing element ResourceKey[minecraft:trim_material / biomesoplenty:glowworm_silk]`.
3. Put the jar back.

### B. Vanilla-compatible server (e.g. DonutSMP, brand `DonutFolia (SchengenVelocity)`)

1. Launch, join the server.
2. Expected: **you join normally.**
3. `logs/latest.log` contains something like:

   ```
   [ModRegFixer] Joined donutsmp.net (brand: DonutFolia (SchengenVelocity), Fabric registry sync: no)
   [ModRegFixer] Server data namespaces: minecraft
   [ModRegFixer] Client content the server does not provide (stays installed, unused here): biomesoplenty (… entries), glitchcore (…), terrablender (…)
   [ModRegFixer] Filtered 3 item component(s) referencing content this server does not have: Biomes O' Plenty (biomesoplenty 26.3.0.0.13) x3
   ```

4. Run `/modregfixer` in chat for a status summary, and `/modregfixer report` for every filtered entry:

   ```
   biomesoplenty:glowworm_silk -> minecraft:provides_trim_material (missing minecraft:trim_material / biomesoplenty:glowworm_silk)
   biomesoplenty:rose_quartz_chunk -> minecraft:provides_trim_material (missing minecraft:trim_material / biomesoplenty:rose_quartz)
   biomesoplenty:music_disc_wanderer -> minecraft:jukebox_playable (missing minecraft:jukebox_song / biomesoplenty:wanderer)
   ```

5. Check that client-only mods still work: Sodium video settings, Iris shaders, minimap, JEI overlay, AppleSkin HUD, etc.
6. No BOP biomes or blocks appear in the world. The server generates the terrain and has none, which is expected.
7. If you have creative access on a test server: BOP items do not appear in the creative tabs/search there.

### C. Back to singleplayer (proves nothing was permanently changed)

1. Disconnect, open a BOP singleplayer world (or create one).
2. Expected: BOP worldgen, blocks and items all work.
3. `/modregfixer` reports *"Singleplayer / integrated server: all mod content is fully active"*.
4. Glowworm silk and rose quartz chunks are valid trim materials again (put one in a smithing table with
   an armor trim template), and the Wanderer disc plays in a jukebox.

### D. Server *with* BOP (optional)

On a Fabric server or LAN world with BOP: the log says `No client content needed filtering for this server.`
Everything works as normal.

### E. Proxy server switching (optional)

On a Velocity network, switch between backends (`/server …`). Each switch re-enters configuration. A
new report is logged for each backend.

## 3. Rollback

ModRegFixer changes nothing on disk except its own config file. Nothing in your worlds, registries
or other mods is modified.

1. Close Minecraft.
2. Delete `mods/modregfixer-*.jar`.
3. Optionally delete `config/modregfixer.properties`.

To keep it installed but switch it off: set `enabled=false` in `config/modregfixer.properties` (or
launch with `-Dmodregfixer.enabled=false`). Behaviour is then exactly vanilla.

## 4. Reporting a problem

Enable `debug=true`, reproduce, then attach `logs/latest.log` (the `[ModRegFixer]` lines include
a full server report) to an issue at https://github.com/cmdralph/ModRegFixer/issues.
