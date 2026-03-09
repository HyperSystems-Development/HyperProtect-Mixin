# Feature Detection

## System Properties

Each mixin interceptor sets system properties on load. These are also pre-declared by the plugin entry point during `setup()` so consumer mods can detect features immediately.

### Core Properties

| Property | Purpose |
|----------|---------|
| `hyperprotect.bridge.active` | Bridge initialized (`"true"` when loaded) |
| `hyperprotect.bridge.version` | Bridge version (e.g., `"1.0.0"`) |
| `hyperprotect.mode` | `"standalone"` or `"compatible"` (when OrbisGuard-Mixins detected) |

### Per-Interceptor Properties

Properties are organized by the mixin class that sets them. In standalone mode, all properties are set. In compatible mode (OrbisGuard detected), only properties from the 15 unique HP mixins are pre-declared.

#### Standalone Interceptors (dedicated mixin classes)

| Property | Interceptor Class | Hook Slot |
|----------|-------------------|-----------|
| `hyperprotect.intercept.block_break` | HarvestInterceptor | `block_break` (0) |
| `hyperprotect.intercept.explosion` | ExplosionInterceptor | `explosion` (1) |
| `hyperprotect.intercept.fire_spread` | FlameTickInterceptor | `fire_spread` (2) |
| `hyperprotect.intercept.builder_tools` | PasteInterceptor | `builder_tools` (3) |
| `hyperprotect.intercept.item_pickup` | ProximityLootInterceptor | `item_pickup` (4) |
| `hyperprotect.intercept.death_drop` | DeathLootInterceptor | `death_drop` (5) |
| `hyperprotect.intercept.durability` | WearInterceptor | `durability` (6) |
| `hyperprotect.intercept.workbench_context` | BenchPositionCapture | `container_access` (7) |
| `hyperprotect.intercept.container_access` | CraftingGateInterceptor | `container_access` (7) |
| `hyperprotect.intercept.spawn_marker` | MarkerSpawnGate | `mob_spawn` (8) |
| `hyperprotect.intercept.world_spawn` | ChunkSpawnGate | `mob_spawn` (8) |
| `hyperprotect.intercept.npc_spawn` | NpcAdditionGate | `mob_spawn` (8) |
| `hyperprotect.intercept.entity_load` | EntityLoadGate | `mob_spawn` (8) |
| `hyperprotect.intercept.command` | CommandGateInterceptor | `command` (11) |
| `hyperprotect.intercept.interaction_log` | SpawnLogFilter | `interaction_log` (12) |
| `hyperprotect.intercept.interaction_entry_desync` | EntryDesyncFilter | `interaction_log` (12) |
| `hyperprotect.intercept.interaction_chain_desync` | ChainDesyncFilter | `interaction_log` (12) |
| `hyperprotect.intercept.entity_damage` | EntityDamageInterceptor | `entity_damage` (16) |
| `hyperprotect.intercept.block_place` | BlockPlaceInterceptor | `block_place` (18) |
| `hyperprotect.intercept.respawn` | RespawnInterceptor | `respawn` (22) |
| `hyperprotect.intercept.capture_crate_entity` | CaptureCrateGate | `use` (20) |
| `hyperprotect.intercept.crafting_resource` | CraftingResourceFilter | `crafting_resource` (23) |
| `hyperprotect.intercept.map_marker_filter` | MapMarkerFilter | `map_marker` (24) |
| `hyperprotect.intercept.shared_marker_filter` | SharedMarkerFilter | `map_marker` (24) |
| `hyperprotect.intercept.fluid_spread` | FlameTickInterceptor | `fluid_spread` (25) |
| `hyperprotect.intercept.prefab_spawn` | PrefabSpawnInterceptor | `prefab_spawn` (26) |
| `hyperprotect.intercept.projectile_launch` | ProjectileLaunchInterceptor | `projectile_launch` (27) |
| `hyperprotect.intercept.mount` | MountInterceptor | `mount` (28) |
| `hyperprotect.intercept.barter_trade` | BarterTradeInterceptor | `barter_trade` (29) |

#### SimpleBlockInteractionGate (consolidated — 20 interaction types)

All set by `SimpleBlockInteractionGate` which intercepts `SimpleBlockInteraction.interactWithBlock()`:

| Property | Intercepted Class | Hook Slot |
|----------|-------------------|-----------|
| `hyperprotect.intercept.use_block` | UseBlockInteraction | `use` (20) |
| `hyperprotect.intercept.break_block_interaction` | BreakBlockInteraction | `block_break` (0) |
| `hyperprotect.intercept.change_block` | ChangeBlockInteraction | `block_place` (18) |
| `hyperprotect.intercept.use` | ChangeStateInteraction | `use` (20) |
| `hyperprotect.intercept.hammer` | CycleBlockGroupInteraction | `hammer` (19) |
| `hyperprotect.intercept.crop_harvest` | HarvestCropInteraction | `use` (20) |
| `hyperprotect.intercept.farming_stage` | ChangeFarmingStageInteraction | `use` (20) |
| `hyperprotect.intercept.fertilize` | FertilizeSoilInteraction | `use` (20) |
| `hyperprotect.intercept.watering_can` | UseWateringCanInteraction | `use` (20) |
| `hyperprotect.intercept.capture_crate` | UseCaptureCrateInteraction | `use` (20) |
| `hyperprotect.intercept.coop` | UseCoopInteraction | `use` (20) |
| `hyperprotect.intercept.teleporter` | TeleporterInteraction | `teleporter` (9) |
| `hyperprotect.intercept.portal_entry` | EnterPortalInteraction | `portal` (10) |
| `hyperprotect.intercept.portal_return` | ReturnPortalInteraction | `portal` (10) |
| `hyperprotect.intercept.instance_config` | TeleportConfigInstanceInteraction | `portal` (10) |
| `hyperprotect.intercept.seat` | SeatingInteraction | `seat` (21) |
| `hyperprotect.intercept.minecart_spawn` | SpawnMinecartInteraction | `block_place` (18) |
| `hyperprotect.intercept.container_open` | OpenContainerInteraction | `container_open` (17) |
| `hyperprotect.intercept.processing_bench` | OpenProcessingBenchInteraction | `container_open` (17) |
| `hyperprotect.intercept.bench_page` | OpenBenchPageInteraction | `container_open` (17) |

#### SimpleInstantInteractionGate (consolidated — 6 interaction types)

All set by `SimpleInstantInteractionGate` which intercepts `SimpleInstantInteraction.interact()`:

| Property | Intercepted Class | Hook Slot |
|----------|-------------------|-----------|
| `hyperprotect.intercept.instance_teleport` | TeleportInstanceInteraction | `portal` (10) |
| `hyperprotect.intercept.instance_exit` | ExitInstanceInteraction | `portal` (10) |
| `hyperprotect.intercept.hub_portal` | HubPortalInteraction | `portal` (10) |
| `hyperprotect.intercept.item_pickup_manual` | PickupItemInteraction | `item_pickup` (4) |
| `hyperprotect.intercept.npc_use` | UseNPCInteraction | `use` (20) |
| `hyperprotect.intercept.npc_contextual_use` | ContextualUseNPCInteraction | `use` (20) |

#### Context Properties

These system properties are set dynamically during hook evaluation and consumed by hook handlers:

| Property | Set By | Description |
|----------|--------|-------------|
| `hyperprotect.context.interaction` | SimpleBlockInteractionGate, CaptureCrateGate | Interaction class name for routing |
| `hyperprotect.context.npc_role` | SimpleInstantInteractionGate | NPC role name (e.g., "shopkeeper", "quest_giver") — extracted via reflection |
| `hyperprotect.context.block_id` | SimpleBlockInteractionGate | Target block type ID at interaction position |
| `hyperprotect.context.block_state` | SimpleBlockInteractionGate | Target block state ID at interaction position |

## Usage

```java
// Check if HyperProtect-Mixin is loaded at all
if ("true".equals(System.getProperty("hyperprotect.bridge.active"))) {
    // Register hooks
}

// Check specific interceptor
if ("true".equals(System.getProperty("hyperprotect.intercept.block_break"))) {
    bridge.set(0, new MyBlockBreakHook());
}

// Check operating mode
String mode = System.getProperty("hyperprotect.mode"); // "standalone" or "compatible"
```

## OrbisGuard Compatibility Mode

When OrbisGuard-Mixins is detected in `earlyplugins/`, `HyperProtectConfigPlugin` disables 15 conflicting HP mixins, keeping 15 unique mixin classes active:

| Active Mixin | Coverage |
|-------------|----------|
| `SimpleBlockInteractionGate` | use, hammer, seat, container_open, teleporter, portal (block), farming |
| `SimpleInstantInteractionGate` | portal (instant), hub, instance teleport/exit, manual item pickup, NPC use |
| `CaptureCrateGate` | entity capture (capture crate pickup) |
| `BlockPlaceInterceptor` | block_place |
| `EntityDamageInterceptor` | entity_damage |
| `RespawnInterceptor` | respawn |
| `CraftingResourceFilter` | crafting_resource |
| `MapMarkerFilter` | map_marker |
| `ProjectileLaunchInterceptor` | projectile_launch |
| `MountInterceptor` | mount |
| `PrefabSpawnInterceptor` | prefab_spawn |
| `BarterTradeInterceptor` | barter_trade |
| `SharedMarkerFilter` | map_marker (shared markers) |
| `FlameTickInterceptor` | fire_spread, fluid_spread |
| `BenchPositionCapture` | workbench_context (for crafting_resource) |

In compatible mode, only the properties from these 15 classes are pre-declared.

## Spawn Startup Behavior

The mob spawn hooks have special startup behavior to prevent unwanted spawns during server initialization.

### Bridge Flag Slots

| Slot | Index | Type | Default | Purpose |
|------|-------|------|---------|---------|
| `spawn_ready` | 13 | Boolean | `null` (false) | Whether the hook provider has finished initializing |
| `spawn_allow_startup` | 14 | Boolean | `null` (false) | Whether spawns are allowed before provider is ready |

### Behavior Matrix

| Hook Registered | spawn_ready | spawn_allow_startup | Result |
|----------------|-------------|---------------------|--------|
| Yes | — | — | Hook decides |
| No | `true` | — | Allow (no protection mod = no restrictions) |
| No | `false` | `true` | Allow (explicitly allowed during startup) |
| No | `false` | `false` | **Block** (safe default during startup) |

### Setting Ready Flag

When your protection mod finishes loading and is ready to handle spawn checks:

```java
@SuppressWarnings("unchecked")
AtomicReferenceArray<Object> bridge = (AtomicReferenceArray<Object>)
    System.getProperties().get("hyperprotect.bridge");

// Register your spawn hook at slot 8
bridge.set(8, new MySpawnHook());

// Mark spawn as ready at slot 13
bridge.set(13, Boolean.TRUE);
```

If your mod takes time to load data (e.g., loading regions from database), set `spawn_allow_startup` first if you want spawns to proceed during loading:

```java
bridge.set(14, Boolean.TRUE);  // Allow startup spawns
// ... load data async ...
bridge.set(8, new MySpawnHook());  // Register hook
bridge.set(13, Boolean.TRUE);     // Mark as ready
```
