# Changelog

All notable changes to HyperProtect-Mixin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

*No changes yet*

## [1.3.0] - 2026-06-24

**Server versions:** `0.5.6` (release) · `0.6.0-pre.4` (pre-release)

This release brings HyperProtect-Mixin onto Hytale's current API from a single codebase and
ships **two JARs**, built per channel from identical source:

| JAR | Target server | Manifest `ServerVersion` |
|-----|---------------|--------------------------|
| `HyperProtect-Mixin-1.3.0.jar` | Hytale **0.5.3 – 0.5.6** (current stable) | `^0.5.6` |
| `HyperProtect-Mixin-1.3.0-prerelease.jar` | Hytale **0.6.0-pre.4** (pre-release) | `^0.6.0-pre.4` |

> Download the JAR that matches your server. The stable JAR will **not** load on a 0.6.0-pre.4
> server and the pre-release JAR will **not** load on a 0.5.x server (manifest ranges are
> enforced strictly). Because the mixin config is `required: true` / `defaultRequire: 1`, any
> descriptor mismatch is a hard server-start failure — the JARs are not interchangeable.

All 29 interceptors compile and apply on both channels. Apply was verified by exhaustive
bytecode descriptor-matching (`javap`) against both channel JARs and, for the pre-release
channel, by loading under Hyxin on a live **0.6.0-pre.4** server: clean boot, bridge
initialized, zero apply failures (25 mixin targets transformed, including the corrected
`ProximityLootInterceptor`).

### Changed — Hytale 0.5.x ("Update 5")
- **Vectors moved to JOML** — `com.hypixel.hytale.math.vector.Vector3d/3f/3i` → `org.joml.*`;
  accessors `getX()/getY()/getZ()` → `x()/y()/z()`. Updated imports, redirect descriptors, and
  `@Shadow`/handler signatures across Harvest, Explosion, BlockPlace, ProximityLoot, DeathLoot,
  Wear, EntityDamage, ProjectileLaunch, NpcAdditionGate, Mount, Respawn, SimpleBlockInteractionGate,
  SimpleInstantInteractionGate, and CaptureCrateGate. (`Transform`, `Rotation3f`, and `Box` stay in
  `com.hypixel.hytale.math.*`.)
- **`Player` is no longer a `CommandSender`** — `sendMessage(Message)` / `hasPermission(String)`
  moved to `PlayerRef`; all deny-message paths now route through `PlayerRef`.
- **Manifest** — `ServerVersion` is templated as `^${serverVersion}`, resolved per channel
  (`^0.5.6` for the stable JAR, `^0.6.0-pre.4` for the pre-release JAR); the 0.5.x+ SemverRange
  codec rejects a bare non-zero-patch version.

### Fixed (per interceptor) — Hytale 0.5.x
- **ExplosionInterceptor** — the inner `BlockHarvestUtils.performBlockDamage` dropped its leading
  `LivingEntity` param; rewrote the `method=` and `@At` descriptors and the handler, and now detects
  sourceless (explosion) block damage via `ref == null` (the 8-arg overload always forwards `ref = null`).
- **BlockPlaceInterceptor** — `BlockPlaceUtils.placeBlock` is now 15 args: the `Inventory` param was
  removed and two trailing booleans (`quickRetype`, `noPhysics`) were added next to `quickReplace`, with
  both block vectors now `org.joml.Vector3i`. Rewrote the redirect descriptor and pass-through.
- **WearInterceptor** — `Player` no longer overrides `updateItemStackDurability` and the impl moved to
  the static `ItemUtils.updateItemStackDurability`. Retargeted `@Mixin(LivingEntity)` and redirect that
  static call; the allow path re-invokes the static impl (no recursion), so the v1.2.4 inlining workaround
  was removed.
- **ProjectileLaunchInterceptor** — `ProjectileModule.spawnProjectile` gained a 6-arg `(UUID, …)` overload
  and the 5-arg one only delegates to it, so the old `method="*"` + 5-arg redirect matched no call site.
  Retargeted the 6-arg overload and redirect its `commandBuffer.addEntity(holder, SPAWN)` — the point every
  launch path converges on — reading creator/world/position from the enclosing params.
- **NpcAdditionGate** — `NPCPlugin.spawnEntity` now takes `org.joml.Vector3dc` + `Rotation3fc` (not
  `math.vector.Vector3d`/`Vector3f`); updated the `method=` descriptor.
- **RespawnInterceptor** — `Player.tryUseSpawnPoint`'s 5th parameter changed `Player` → `PlayerRef`;
  updated the `@Shadow` stub, the `@Redirect` descriptor, and the handler.
- **MarkerSpawnGate / ChunkSpawnGate** — `SpawningContext.world` is now private; read it via `getWorld()`.
- **CommandGateInterceptor** — the command sender is now a `PlayerRef` (not a `Player`); match `PlayerRef`,
  resolve the `Player` component from it to keep the `evaluateCommand(Player, String)` bridge contract
  unchanged, and message via `PlayerRef`.
- **MapMarkerFilter** — corrected the `MapMarker` parameter package in the `addIgnoreViewDistance` redirect
  descriptor (`com.hypixel.hytale.protocol.packets.worldmap.MapMarker`).
- **SharedMarkerFilter** — `SharedMarkersProvider.update` now emits via `addIgnoreViewDistance` (not `add`);
  retargeted the `@Redirect` and the handler's re-emit calls.
- **ChainDesyncFilter** — `InteractionChain.addTempSyncData` was renamed to `putInteractionSyncData`;
  updated the `method=` selector.

### Added — Hytale 0.6.0-pre.4 ("Update 6") pre-release support
Audited every mixin target (`@Mixin`, `method=`, `@At`, `@Shadow`, `@Redirect`) against the
0.6.0-pre.4 bytecode. Two interceptors needed channel-specific descriptors; the other 27 are
byte-identical across channels and stay shared in `src/main/java`.
- **ExplosionInterceptor** — `BlockHarvestUtils.performBlockDamage` gained a trailing
  `boolean isExplosion` on both overloads in 0.6.0-pre.4 (outer 8→9 args, inner 11→12 args), plus
  a new 13-arg batch overload. The pre-release variant shifts the `method=` selector, the `@At`
  INVOKE target, the `@Redirect` handler signature, and the delegate call by that one parameter
  and forwards `isExplosion` unchanged. (This is also a hard compile error on 0.6.0-pre.4, caught
  by the build; the release variant is unchanged.)
- **ProximityLootInterceptor** — `SpatialStructure.closest(Vector3d)` became `closest(Vector3dc)`
  (the read-only JOML interface) in 0.6.0-pre.4. The pre-release variant retargets the `@At`
  descriptor and the redirect handler's parameter type. This redirect has no `require=` override,
  so on a 0.6.0-pre.4 server the old descriptor would match 0 sites and **hard-fail startup** — the
  compiler cannot catch this; it was caught by bytecode audit and confirmed by the live load-test.
- **Deprecations** — `Player.getPlayerRef()` and `Entity.getUuid()` are deprecated-for-removal on
  both channels (still present). HarvestInterceptor's item-pickup deny path now reuses the
  already-resolved `PlayerRef`; the remaining unavoidable sites (redirect targets / handlers with no
  component accessor) are `@SuppressWarnings("removal")`. Source-only, so the mixin bytecode is
  unchanged. Both channels now compile with no `[removal]` warnings.

### Build
- **Per-channel mixin source sets** — `build.gradle` adds `src/${hytale_channel}/java` to the main
  source set. Only the two descriptor-drifting interceptors live there (`src/release/java` +
  `src/pre-release/java`); every other interceptor is shared. `hyperprotect.mixin.json` references
  the same FQCNs on both channels, so the mixin list and counts are identical.

### Notes
- The 30-slot bridge protocol and verdict contract are unchanged (HyperFactions 0.14.0 consumes
  them); the OG-coexistence `SAFE_MIXINS` set (14) and `totalMixins = 29` are unchanged.
- The 0.5.x fixes above were verified on a live 0.5.3 server in 1.3.0's original cut and their
  descriptors re-confirmed against 0.5.6 via `javap`; `FlameTickInterceptor`, `BenchPositionCapture`,
  `EntityLoadGate`, `PrefabSpawnInterceptor`, `SpawnLogFilter`, and `EntryDesyncFilter` required no
  changes on either channel.
- **Known pre-existing limitation (not a regression):** `MarkerSpawnGate` (spawn-marker NPC gating)
  redirects a no-arg `SpawningContext.canSpawn()` that no longer exists — `SpawnMarkerEntity` calls
  the 2-arg `canSpawn(boolean, boolean)` on both channels — so it injects nothing. Its `require = 0`
  keeps this non-fatal in normal operation (verified: clean boot on 0.6.0-pre.4); world/chunk spawn
  gating via `ChunkSpawnGate` is unaffected. Restoring marker gating would change spawn behavior and
  is deferred as out of scope for this compatibility release.

## [1.2.4] - 2026-04-02

**Server Version:** `2026.03.26-89796e57b`

### Fixed
- **StackOverflowError in WearInterceptor** — calling `instance.updateItemStackDurability()` on the allow path dispatched via `INVOKEVIRTUAL` back to `Player`'s override, re-triggering the redirect in an infinite loop. Fixed by inlining `LivingEntity.updateItemStackDurability()` directly ([#3](https://github.com/HyperSystems-Development/HyperProtect-Mixin/issues/3))

## [1.2.3] - 2026-03-29

**Server Version:** `2026.03.26-89796e57b`

### Fixed
- **WearInterceptor rewritten** — `Player.canDecreaseItemStackDurability()` was removed; rewrote from `@Overwrite` to `@Redirect` on `LivingEntity.updateItemStackDurability()`, returning null to prevent durability loss when hook denies
- **BlockPlaceInterceptor updated** — `BlockPlaceUtils.placeBlock()` gained a `quickReplace` boolean parameter; updated redirect target descriptor and pass-through
- **StateData.getId() removed** — replaced with `getStateForBlock(blockType.getId())` in `SimpleBlockInteractionGate`
- **Item package paths moved** — `ItemStack`, `ItemContainer`, `ItemStackSlotTransaction` moved to `inventory` subpackages; updated all imports

### Changed
- **Server version pinning** — manifest `ServerVersion` changed from wildcard `*` to `${serverVersion}` (resolved at build time from Maven), preventing the plugin from loading on incompatible server builds
- **Build configuration** — `processResources` now expands `serverVersion` into `manifest.json` alongside the existing `version` property

## [1.2.2] - 2026-03-11

### Fixed
- **NPE on /kill in safezone** — redirecting `getGameMode()` instead of `getComponent()` in the respawn interceptor prevents a NullPointerException when players use `/kill` in a protected zone ([#2](https://github.com/HyperSystems-Development/HyperProtect-Mixin/issues/2))

## [1.2.1] - 2026-03-09

### Fixed
- **Critical: workbench crash (NoClassDefFoundError)** — `BenchPositionCapture` and `CraftingResourceFilter` directly referenced the plugin-classloader `CraftingContext` class from mixin-injected code running in the server classloader, causing `NoClassDefFoundError: com/hyperprotect/mixin/bridge/CraftingContext` and disconnecting players when opening a workbench
- **Critical: world crash (NoClassDefFoundError)** — `CraftingResourceFilter` used `@Inject` with `CallbackInfoReturnable` parameter, and `BarterTradeInterceptor` used `@Inject` with `CallbackInfo` — both reference Mixin library classes not available on the `TransformingClassLoader` classpath at runtime, crashing the world thread and disconnecting all players
- **CraftingResourceFilter removed** — merged slot 23 (`crafting_resource`) check into `CraftingGateInterceptor`'s existing `@Redirect`, which already has access to player UUID, world name, and bench coords via `@Shadow` fields. Eliminates the separate mixin class and its unsafe `@Inject`
- **BarterTradeInterceptor rewritten** — converted from `@Inject`+`CallbackInfo` to `@Redirect` on `data.getQuantity()`. Returns `0` if the hook denies the trade, which triggers the method's built-in `requestedQuantity <= 0` early return — no Mixin library types in generated bytecode
- Replaced direct `CraftingContext` class references with `System.getProperties()`-backed ThreadLocals using bootstrap-class types only (`hyperprotect.ctx.craftingPlayerUuid`, `hyperprotect.ctx.benchCoords`)
- `BenchPositionCapture` redirect now wraps all context capture in try-catch — workbench opening can never be blocked by a mixin error
- `CraftingGateInterceptor` redirect now wraps entire method body in try-catch with fallback to original `isValidBenchForRecipe()` on error
- `CraftingContext` bridge class converted from static fields to method accessors delegating to the same system property keys

### Changed
- Mixin count reduced from 30 to 29 (CraftingResourceFilter merged into CraftingGateInterceptor)
- OrbisGuard-compatible SAFE_MIXINS reduced from 15 to 14 (CraftingResourceFilter removed)

## [1.2.0] - 2026-03-08

### Added
- **7 new protection hooks** (30 interceptors, 27 hooks total):
  - **CraftingResourceFilter** (slot 23) — gates crafting resource validation at the recipe level, with bench position context from `BenchPositionCapture`
  - **MapMarkerFilter** (slot 24) — filters world map marker visibility per-player via `OtherPlayersMarkerProvider`
  - **FluidSpread** (slot 25) — intercepts non-fire fluid spreading (water, lava) in `FluidTicker.process()`, extending the existing `FlameTickInterceptor`
  - **PrefabSpawnInterceptor** (slot 26) — gates prefab entity spawning during `Store.addEntity()` for `LOAD` reason entities
  - **ProjectileLaunchInterceptor** (slot 27) — intercepts projectile launches via `ProjectileModule.spawnProjectile()`
  - **MountInterceptor** (slot 28) — intercepts mount/ride entity interactions via `DamageEntityInteraction`
  - **BarterTradeInterceptor** (slot 29) — gates barter/trade NPC interactions
- **Bridge expanded to 30 slots** (was 24) — `AtomicReferenceArray<Object>(30)` accommodates all new hooks
- **CraftingContext bridge class** — ThreadLocal-based cross-mixin context sharing for bench position and player UUID between `BenchPositionCapture` and `CraftingResourceFilter`
- **NPC role context extraction** — `SimpleInstantInteractionGate` extracts NPC role names via reflection and stores in `hyperprotect.context.npc_role` system property for consumer mods to classify tame vs interact actions
- **Block type context extraction** — `SimpleBlockInteractionGate` extracts `BlockType` ID and state from the world at the target position, stored in `hyperprotect.context.block_id` and `hyperprotect.context.block_state` system properties
- **SharedMarkerFilter** (slot 24) — filters shared (user-placed) map markers per-viewer based on faction relationships. Extracts creator UUID from `PlacedByMarkerComponent` and checks via the same `filterSharedMarker` hook method on the map_marker_filter bridge slot
- **SAFE_MIXINS expanded** — 15 unique mixin classes (was 10) for OrbisGuard compatibility mode. All new interceptors have no OG equivalent and are always active
- Per-interceptor system properties for all 7 new hooks (`hyperprotect.intercept.crafting_resource`, `hyperprotect.intercept.map_marker_filter`, `hyperprotect.intercept.shared_marker_filter`, `hyperprotect.intercept.fluid_spread`, `hyperprotect.intercept.prefab_spawn`, `hyperprotect.intercept.projectile_launch`, `hyperprotect.intercept.mount`, `hyperprotect.intercept.barter_trade`)

### Changed
- **Multi-signal OrbisGuard detection** — improved `HyperProtectConfigPlugin` to check system properties (`orbisguard.mixins.loaded`), bridge object (`orbisguard.bridge`), and JAR scan fallback for more reliable OG detection
- `BenchPositionCapture` now stores bench position and player UUID in `CraftingContext` ThreadLocals (previously unused capture data)
- **SharedMarkerFilter** — rewritten from `@Inject`+`CallbackInfo` to `@Redirect` on `collector.add(MapMarker)`. The WorldMap thread runs on a separate `TickingThread` whose classloader does not have Mixin library classes — `@Inject` caused `NoClassDefFoundError: CallbackInfo` at runtime. `@Redirect` avoids referencing any Mixin classes in the injected bytecode

### Fixed
- **BarterTradeInterceptor** — fix `InvalidInjectionException` caused by using `Object` instead of `BarterPage.BarterEventData` as the third parameter in `gateTrade`. This broke NPC role building for any NPC with a barter shop interaction (e.g., Klops_Merchant)
- **NpcAdditionGate** — rewrite to target specific 7-arg `spawnEntity` method descriptor instead of `method = "*"` which matched all methods in NPCPlugin. Simplified field structure and removed bare `static {}` initializer block that could cause mixin transformation issues
- **NpcAdditionGate** — fix log label from `[HyperProtect-Mixins]` to `[HyperProtect]` for consistency, add stack trace printing in catch blocks
- **totalMixins count** — corrected from 29 to 30 in `HyperProtectConfigPlugin`

## [1.1.0] - 2026-02-26

**Server Version:** `2026.02.19-1a311a592`

### Added
- **CaptureCrateGate** — new mixin interceptor for entity capture via UseCaptureCrateInteraction. Redirects `getTargetEntity()` in tick0() to check protection before allowing animal pickup. Uses bridge slot 20 (evaluateUse)
- **NPC tame/use interception** — added UseNPCInteraction and ContextualUseNPCInteraction to SimpleInstantInteractionGate HOOK_DEFS (slot 20, evaluateUse)
- CaptureCrateGate added to SAFE_MIXINS in HyperProtectConfigPlugin (6 unique mixins, was 5)
- System properties: `hyperprotect.intercept.capture_crate_entity`, `hyperprotect.intercept.npc_use`, `hyperprotect.intercept.npc_contextual_use`
- Pre-declared new interceptor properties in both standalone and compatible modes

### Fixed
- All 22 interceptors now log full stack traces on error (previously only printed exception class and message), making it possible to identify the exact line causing faults

## [1.0.0] - 2026-02-22

### Added

- **AtomicReferenceArray bridge** — 24-element lock-free bridge stored in `System.getProperties()` for zero-contention cross-classloader hook communication
- **20 protection hooks** across 7 categories:
  - **Building:** block_break (0), explosion (1), fire_spread (2), builder_tools (3), block_place (18), hammer (19), use (20)
  - **Items:** item_pickup (4), death_drop (5), durability (6)
  - **Containers:** container_access (7), container_open (17)
  - **Combat:** entity_damage (16)
  - **Entities:** mob_spawn (8), respawn (22)
  - **Transport:** teleporter (9), portal (10), seat (21)
  - **Commands:** command (11)
  - **Logging:** interaction_log (12)
- **22 mixin interceptors** covering all 20 hooks with multiple injection points for comprehensive coverage
- **Consolidated interaction gates** — `SimpleBlockInteractionGate` covers 20 `SimpleBlockInteraction` subclasses (block use, block state changes, hammer cycling, farming interactions, teleporters, portals, seating, minecarts, containers, crafting benches) via `HashMap<String, HookDef>` dispatch table; `SimpleInstantInteractionGate` covers 4 `SimpleInstantInteraction` subclasses (instance teleport, instance exit, hub portal, config instance)
- **OrbisGuard-Mixins compatibility mode** — `HyperProtectConfigPlugin` detects OrbisGuard-Mixins JARs in `earlyplugins/` at mixin load time and disables 17 conflicting HP mixins, keeping 5 unique mixins active. Sets `hyperprotect.mode` to `"compatible"` or `"standalone"` so consumer mods can adapt hook registration
- **Verdict protocol** — standardized ALLOW (0) / DENY_WITH_MESSAGE (1) / DENY_SILENT (2) / DENY_MOD_HANDLES (3)
- **Respawn value hook** — returns `double[3]` override coordinates instead of verdict int
- **Fail-open safety** — all hooks allow actions on error, missing hooks, or negative verdicts
- **System property detection** — `hyperprotect.bridge.active`, `hyperprotect.bridge.version`, `hyperprotect.mode`, and per-interceptor `hyperprotect.intercept.*` properties. Features pre-declared at plugin setup time so consumer mods can detect them during their own `setup()`
- **Spawn startup protection** — configurable spawn blocking during server initialization via `spawn_ready` and `spawn_allow_startup` flag slots
- **ChatFormatter** — token-based `&`-code message formatter with hex colors (`&#RRGGBB`), named colors, bold, italic, monospace, and reset support using sealed interfaces and records
- **FaultReporter** — sampled error logging (first + every 100th) to prevent log flooding
- **HookSlot caching** — eagerly-resolved MethodHandles with volatile identity-checked caching for minimal overhead
- **Deny message deduplication** — `SimpleBlockInteractionGate` applies 500ms per-player cooldown to prevent rapid-fire denial messages
- **JitPack publishing** — `maven-publish` plugin for consumer mods to depend via `com.github.HyperSystems-Development:HyperProtect-Mixin:1.0.0`
- **Build system** — Java 25 toolchain, Hytale server resolved from `maven.hytale.com`, `fileTree` glob for Hyxin libs, centralized version expansion in `manifest.json`
- **Complete documentation** — getting-started guide, hook reference, integration patterns, code examples, feature detection guide, and OrbisGuard migration guide
