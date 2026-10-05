# ModRegFixer

**Join vanilla, Paper, Folia and Velocity servers with your gameplay mods installed. No second
instance, no moving jars around.**

A client-side Fabric mod for **Minecraft 26.3** (also 26.2 and 26.1). It fixes crashes like this one,
which happen when a content mod such as Biomes O' Plenty is installed and you join a server that doesn't have it:

```
java.lang.IllegalStateException: Missing element ResourceKey[minecraft:trim_material / biomesoplenty:glowworm_silk]
    at net.minecraft.core.HolderGetter$Provider.getOrThrow
    at net.minecraft.world.item.Item$Properties.lambda$delayedHolderComponent$0
    at net.minecraft.core.component.DataComponentInitializers…
    at net.minecraft.client.multiplayer.RegistryDataCollector.updateComponents
    at net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl.handleConfigurationFinished
```

| | |
|---|---|
| **Singleplayer** | Completely unaffected. Every mod, item, trim material and worldgen feature works as normal. |
| **Modded server that has the mod** | Unaffected. The server provides the content, so nothing is filtered. |
| **Vanilla / Paper / Folia / Velocity** | You connect. Item components that point at content the server doesn't have (e.g. "this item is a trim material" for BOP's glowworm silk) are left out *for that connection only*. Everything else, including all client-only mods, keeps working. |

It is **not** "disable all mods in multiplayer". Sodium, Iris, AppleSkin, Xaero's, JEI, Litematica,
ModernFix, etc. are never touched. It works at the level of individual registry entries, using the
server's own registry data as the source of truth.

## Install

1. Install Fabric Loader **0.19.5+** and Fabric API for your Minecraft version.
2. Download the jar for your Minecraft version:
   * `modregfixer-<version>+26.3.jar` for 26.3
   * `modregfixer-<version>+26.2.jar` for 26.2
   * `modregfixer-<version>+26.1.jar` for 26.1, 26.1.1, 26.1.2
3. Put it in your `mods/` folder next to your other mods. Done.

It only needs to be on the **client**. Servers don't need anything.

## How it works (short version)

When you join a remote server, Minecraft 26.x re-evaluates **every item's default components** against
the **server's** registries (`RegistryDataCollector.updateComponents`). BOP's `glowworm_silk`
says *"I am the trim material `biomesoplenty:glowworm_silk`"*. The server has no such trim material, and
vanilla throws.

ModRegFixer wraps each server-dependent component of each item individually (at
`Item.Properties.delayedHolderComponent` / `delayedComponent`). On a remote server, a component whose
target isn't in the server's registries is simply not added, and the item keeps everything else. Nothing is
faked, no registry is changed, and singleplayer rebuilds everything from scratch each time you load a world.

Full details, including the exact 26.3 code paths, why the approach is safe, and what is detected:
**[docs/TECHNICAL.md](docs/TECHNICAL.md)**.

For Biomes O' Plenty 26.3.0.0.13, on a vanilla server, ModRegFixer filters exactly three components:

```
biomesoplenty:glowworm_silk       -> minecraft:provides_trim_material   (missing minecraft:trim_material / biomesoplenty:glowworm_silk)
biomesoplenty:rose_quartz_chunk   -> minecraft:provides_trim_material   (missing minecraft:trim_material / biomesoplenty:rose_quartz)
biomesoplenty:music_disc_wanderer -> minecraft:jukebox_playable         (missing minecraft:jukebox_song / biomesoplenty:wanderer)
```

## Commands (client-side)

| Command | |
|---|---|
| `/modregfixer` | Status of the current connection: server, brand, what was filtered and from which mod |
| `/modregfixer report` | Full report into `logs/latest.log` (address, server registry namespaces, client-only content, every filtered entry with its mod) |
| `/modregfixer debug on` / `off` | Verbose logging on every join (saved to config) |

## Config: `config/modregfixer.properties`

```properties
enabled=true                       # false = the mod does nothing at all
debug=false                        # log every filtered component + full server report on join
hideUnsupportedCreativeItems=true  # on non-Fabric servers, hide creative-menu items the server can't know
```

Each option can be forced with a JVM argument: `-Dmodregfixer.debug=true`, `-Dmodregfixer.enabled=false`, …

## Build from source

Requires **JDK 25** (Minecraft 26.x requires Java 25).

```bash
./gradlew build                      # Minecraft 26.3 → build/libs/modregfixer-1.0.0+26.3.jar
./gradlew build -Pmc_target=26.2     # Minecraft 26.2
./gradlew build -Pmc_target=26.1     # Minecraft 26.1.x
./gradlew runClient                  # dev client with the mod loaded
```

`build` also runs the unit tests, which boot the real Minecraft classes with the mod's mixins applied.
Exact versions are pinned in `gradle.properties` and `versions/*.properties`. Minecraft 26.x is
unobfuscated, so **no mappings** are used. CI builds and tests all three versions on every push.

## Testing and rollback

See **[docs/TESTING.md](docs/TESTING.md)** for the step-by-step BOP test procedure.

Rollback: delete `mods/modregfixer-*.jar` (and optionally `config/modregfixer.properties`). The mod
changes nothing on disk except its own config file.

## Limitations

* **Modded items/blocks don't become usable on a vanilla server.** The server simply doesn't have them.
  ModRegFixer gets you connected and keeps your client mods working. It doesn't fabricate content.
* **Only content registered through the standard paths is filtered**: `Item.Properties` delayed components
  (used by `trimMaterial`, `jukeboxPlayable`, `fireResistant`, `potPattern`, `spear`, `axe/hoe/shovel`,
  `loweredMobVisibility`, and any mod calling them directly) and Fabric API's `DefaultItemComponentEvents`.
  If a mod registers component initializers some other way, and those fail on a server, ModRegFixer
  can't remove them safely. It then logs a precise report (item, mod, missing entry) and the connection fails
  as it would without the mod. Nothing is corrupted. Please open an issue with the log.
* This handles the **configuration-phase component crash**. It doesn't change protocol behaviour, so a mod that sends
  custom packets the server rejects, or that requires a server-side counterpart for gameplay, still needs that server support.
* Not for Minecraft 1.21.x or older: that crash can't happen there (`DataComponentInitializers` was introduced in 26.1).

## License

MIT, see [LICENSE](LICENSE).
