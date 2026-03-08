# Changelog

All notable changes to HyperProtect-Mixin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **7 new protection hooks** (29 interceptors, 27 hooks total):
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
- Per-interceptor system properties for all 7 new hooks (`hyperprotect.intercept.crafting_resource`, `hyperprotect.intercept.map_marker_filter`, `hyperprotect.intercept.fluid_spread`, `hyperprotect.intercept.prefab_spawn`, `hyperprotect.intercept.projectile_launch`, `hyperprotect.intercept.mount`, `hyperprotect.intercept.barter_trade`)

### Changed
- **Multi-signal OrbisGuard detection** — improved `HyperProtectConfigPlugin` to check system properties (`orbisguard.mixins.loaded`), bridge object (`orbisguard.bridge`), and JAR scan fallback for more reliable OG detection
- `BenchPositionCapture` now stores bench position and player UUID in `CraftingContext` ThreadLocals (previously unused capture data)

### Changed
- **SharedMarkerFilter** — rewritten from `@Inject`+`CallbackInfo` to `@Redirect` on `collector.add(MapMarker)`. The WorldMap thread runs on a separate `TickingThread` whose classloader does not have Mixin library classes — `@Inject` caused `NoClassDefFoundError: CallbackInfo` at runtime. `@Redirect` avoids referencing any Mixin classes in the injected bytecode

### Fixed
- **BarterTradeInterceptor** — fix `InvalidInjectionException` caused by using `Object` instead of `BarterPage.BarterEventData` as the third parameter in `gateTrade`. This broke NPC role building for any NPC with a barter shop interaction (e.g., Klops_Merchant)
- **NpcAdditionGate** — rewrite to target specific 7-arg `spawnEntity` method descriptor instead of `method = "*"` which matched all methods in NPCPlugin. Simplified field structure and removed bare `static {}` initializer block that could cause mixin transformation issues

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
