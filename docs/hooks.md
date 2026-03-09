# Hook Reference

All 27 hooks organized by category. Each hook is registered at a specific slot index in the `hyperprotect.bridge` array.

All hooks return `int` verdicts unless noted otherwise:
- `0` = ALLOW
- `1` = DENY_WITH_MESSAGE (calls `fetch*DenyReason()`)
- `2` = DENY_SILENT
- `3` = DENY_MOD_HANDLES

## Building

### Slot 0: `block_break`

Intercepts block breaking and interactive item pickup.

**Methods:**

| Method | Signature | Return | Required |
|--------|-----------|--------|----------|
| `evaluate` | `int evaluate(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int | Yes |
| `evaluatePickup` | `int evaluatePickup(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int | No |
| `fetchDenyReason` | `String fetchDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null | No |
| `fetchPickupDenyReason` | `String fetchPickupDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null | No |

**Intercepted actions:** Block harvesting via `BlockHarvestUtils.performPickupByInteraction()`. The `evaluatePickup` method is called for interactive item pickups that follow block breaks.

**Thread safety:** Called from world thread. Must be thread-safe.

---

### Slot 1: `explosion`

Intercepts explosion block damage.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateExplosion` | `int evaluateExplosion(World world, int x, int y, int z)` | verdict int (0 or 2 only) |

**Intercepted actions:** Explosion damage to blocks via `BlockHarvestUtils.performBlockDamage()`. Only fires for damage with no entity source.

**Note:** No player context — only returns 0 (allow) or 2 (silent deny). The `World` parameter is a `com.hypixel.hytale.server.core.universe.world.World` instance.

---

### Slot 2: `fire_spread`

Intercepts fire fluid spreading.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateFlame` | `int evaluateFlame(String worldName, int x, int y, int z)` | verdict int (0 or 2 only) |

**Intercepted actions:** Fire fluid tick/spread via `FluidTicker.process()`. Only intercepts fire fluid tickers (class name contains "Fire").

---

### Slot 3: `builder_tools`

Intercepts builder tool paste operations.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluatePaste` | `int evaluatePaste(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int |
| `fetchPasteDenyReason` | `String fetchPasteDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null |

**Intercepted actions:** Clipboard paste via `BuilderToolsPacketHandler.handleBuilderToolPasteClipboard()`.

---

### Slot 25: `fluid_spread`

Intercepts non-fire fluid spreading (water, lava).

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateFluidSpread` | `int evaluateFluidSpread(String worldName, int x, int y, int z)` | verdict int (0 or 2 only) |

**Intercepted actions:** Non-fire fluid tick/spread via `FluidTicker.process()`. Only intercepts fluid tickers that are NOT fire-type (fire has its own hook at slot 2). Handled by the same `FlameTickInterceptor` mixin.

**Note:** No player context — only returns 0 (allow) or 2 (silent deny).

**Use cases:** Prevent water/lava griefing in claimed territory. Block fluid placement in safe zones.

---

### Slot 18: `block_place`

Intercepts block placement.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateBlockPlace` | `int evaluateBlockPlace(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int |
| `fetchBlockPlaceDenyReason` | `String fetchBlockPlaceDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null |

**Intercepted actions:** Block placement via `PlaceBlockInteraction.tick0()`. Position is the target block position from the client state. Only checked on the initial placement tick (not drag placement ticks).

---

### Slot 19: `hammer`

Intercepts hammer block cycling (variant rotation).

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateHammer` | `int evaluateHammer(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int |
| `fetchHammerDenyReason` | `String fetchHammerDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null |

**Intercepted actions:** Hammer block cycling via `CycleBlockGroupInteraction.interactWithBlock()`. The hammer tool rotates block variants (e.g., different wood plank orientations). Position is the target block.

**Use cases:** Prevent non-members from modifying block variants in claimed territory.

---

### Slot 20: `use`

Intercepts block state changes, entity capture, and NPC interactions — any block/entity with toggleable, interactive, or capturable state.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateUse` | `int evaluateUse(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int |
| `fetchUseDenyReason` | `String fetchUseDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null |

**Intercepted actions** (via `SimpleBlockInteractionGate`):
- `UseBlockInteraction` — General block use interactions
- `ChangeStateInteraction` — Toggleable block state changes
- `HarvestCropInteraction` — Crop harvesting
- `ChangeFarmingStageInteraction` — Farming stage changes
- `FertilizeSoilInteraction` — Soil fertilization
- `UseWateringCanInteraction` — Watering can use
- `UseCaptureCrateInteraction` — Capture crate placement (releasing animal)
- `UseCoopInteraction` — Animal coop use

**Intercepted actions** (via `CaptureCrateGate`):
- Entity capture via `UseCaptureCrateInteraction.tick0()` — picking up animals with capture crate. Sets interaction context to `"entity-capture"` to distinguish from crate placement.

**Intercepted actions** (via `SimpleInstantInteractionGate`):
- `UseNPCInteraction` — F-key NPC taming/use
- `ContextualUseNPCInteraction` — Contextual NPC interactions (shop, quest, dialogue)

Position is the target block or entity position.

**Interaction routing:** Consumer mods can read the interaction class name from `hyperprotect.context.interaction` to distinguish between block use, crate pickup, crate placement, and NPC taming. For example:
- `"UseCaptureCrateInteraction(entity-capture)"` → capture crate pickup (animal being caught, via `CaptureCrateGate`)
- `"com.hypixel.hytale.builtin.adventure.farming.interactions.UseCaptureCrateInteraction"` → capture crate placement (animal being released, via `SimpleBlockInteractionGate`)
- `"com.hypixel.hytale.server.npc.interactions.UseNPCInteraction"` → NPC taming/use (via `SimpleInstantInteractionGate`)
- `"com.hypixel.hytale.server.npc.interactions.ContextualUseNPCInteraction"` → contextual NPC interactions (via `SimpleInstantInteractionGate`)

**Use cases:** Prevent outsiders from interacting with blocks, picking up/placing animals, or taming NPCs in claimed territory. Protect farming operations and interactive blocks.

---

## Items

### Slot 4: `item_pickup`

Intercepts automatic (proximity) item pickup.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluate` | `int evaluate(UUID playerUuid, String worldName, double x, double y, double z)` | verdict int (0 or 2 only) |

**Intercepted actions:** Proximity item pickup via `PlayerItemEntityPickupSystem.tick()`.

**Note:** Uses `double` coordinates (entity position). No messaging — returns 0 or 2 only.

---

### Slot 5: `death_drop`

Controls keep-inventory-on-death behavior.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateDeathLoot` | `int evaluateDeathLoot(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int (0 or 2 only) |

**Intercepted actions:** Death item drops via `DeathSystems.DropPlayerDeathItems.onComponentAdded()`. Position is the player's death location.

**Note:** Verdict 0 = drop normally, verdict 2 = keep inventory.

---

### Slot 6: `durability`

Prevents item durability loss.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateWear` | `int evaluateWear(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int (0 or 2 only) |

**Intercepted actions:** Durability decrease via `Player.canDecreaseItemStackDurability()`. Position is the player's current location.

**Note:** Verdict 0 = allow durability loss, verdict 2 = prevent.

---

### Slot 7: `container_access`

Controls workbench/crafting container access.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateCrafting` | `int evaluateCrafting(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int |
| `fetchCraftingDenyReason` | `String fetchCraftingDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null |

**Intercepted actions:** Crafting via `CraftingManager.craftItem()`. Position is the crafting bench block location (captured by `BenchPositionCapture`).

---

### Slot 23: `crafting_resource`

Intercepts crafting resource validation at the recipe level.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateChestAccess` | `boolean evaluateChestAccess(UUID playerUuid, String worldName, int chestX, int chestY, int chestZ, int benchX, int benchY, int benchZ)` | `true` = allow, `false` = deny |

**Intercepted actions:** Crafting resource validation in `CraftingManager.isValidBenchForRecipe()`. Uses bench coordinates captured by `BenchPositionCapture` via the `CraftingContext` bridge class.

**Note:** Player UUID and bench position are provided via ThreadLocal context from `BenchPositionCapture`. This hook returns `boolean` instead of the standard `int` verdict.

**Use cases:** Prevent crafting with protected workbenches. Restrict recipe access based on territory ownership.

---

### Slot 29: `barter_trade`

Intercepts barter/trade NPC interactions.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateTrade` | `int evaluateTrade(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int |
| `fetchTradeDenyReason` | `String fetchTradeDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null |

**Intercepted actions:** Barter/trade interactions with NPCs. Position is the player's location.

**Use cases:** Restrict NPC trading in claimed territory. Block trading with specific NPCs in safe zones.

---

## World Map

### Slot 24: `map_marker`

Filters world map marker visibility per player. This slot hosts two methods — one for player markers and one for shared markers.

#### Player Markers (MapMarkerFilter)

| Method | Signature | Return |
|--------|-----------|--------|
| `filterPlayerMarker` | `int filterPlayerMarker(UUID viewerUuid, UUID targetUuid, String worldName, int x, int y, int z)` | verdict int (0 or 2 only) |

**Intercepted actions:** Map marker rendering in `OtherPlayersMarkerProvider.update()`. Each player marker is checked individually before being added to the viewer's map.

**Parameters:**
- `viewerUuid` — The UUID of the player viewing the map
- `targetUuid` — The UUID of the player whose marker is being checked (extracted from `"Player-{uuid}"` marker ID)
- `worldName` — The world name
- `x, y, z` — The marker's world position

**Note:** No messaging — returns 0 (show marker) or >0 (hide marker). The viewer UUID is captured from the enclosing `update()` method via a secondary redirect. Self-markers (viewer == target) are never filtered.

#### Shared Markers (SharedMarkerFilter)

| Method | Signature | Return |
|--------|-----------|--------|
| `filterSharedMarker` | `int filterSharedMarker(UUID viewerUuid, UUID creatorUuid, String worldName, float x, float z)` | verdict int (0 or 2 only) |

**Intercepted actions:** Shared marker rendering in `SharedMarkersProvider.update()`. Each user-placed shared marker is checked before being sent to the viewer.

**Parameters:**
- `viewerUuid` — The UUID of the player viewing the map
- `creatorUuid` — The UUID of the player who placed the marker (from `PlacedByMarkerComponent`)
- `worldName` — The world name
- `x, z` — The marker's world position (`float` precision, no Y component)

**Note:** No messaging — returns 0 (show) or >0 (hide). Self-markers (viewer == creator) are never filtered. If the hook object does not implement `filterSharedMarker`, all shared markers are shown (fail-open). Uses `float` coordinates instead of `int` since shared markers track 2D map positions.

**Use cases:** Hide enemy faction player and shared markers. Show only allied markers in claimed territory. Filter markers by proximity or permission.

---

## Combat

### Slot 16: `entity_damage`

Intercepts player-initiated entity damage (PvP and player-vs-entity).

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateEntityDamage` | `int evaluateEntityDamage(UUID attackerUuid, UUID targetUuid, String worldName, int x, int y, int z)` | verdict int |
| `fetchEntityDamageDenyReason` | `String fetchEntityDamageDenyReason(UUID attackerUuid, UUID targetUuid, String worldName, int x, int y, int z)` | deny message or null |

**Intercepted actions:** Player-initiated damage via `DamageEntityInteraction.tick0()`. Only player attackers are intercepted (non-player damage sources pass through).

**Parameters:**
- `attackerUuid` — The attacking player's UUID (always non-null)
- `targetUuid` — The target player's UUID if the target is a player, `null` otherwise (for mob/entity targets)
- `x, y, z` — Target entity's block position (derived from entity snapshot)

**Use cases:** PvP protection (check `targetUuid != null`), entity protection in claimed territory, safe zones.

**Note:** Deny messages are sent to the **attacker**, not the target.

---

### Slot 27: `projectile_launch`

Intercepts player-initiated projectile launches.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateProjectileLaunch` | `int evaluateProjectileLaunch(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int |

**Intercepted actions:** Projectile spawning via `ProjectileModule.spawnProjectile()`. Only player-initiated projectiles are checked — non-player projectiles (mob AI, etc.) pass through.

**Note:** Currently uses silent deny only (no `fetchProjectileLaunchDenyReason` method). Position is the projectile launch origin.

**Use cases:** Prevent ranged attacks in safe zones. Block projectile launches in protected territory.

---

## Entities

### Slot 8: `mob_spawn`

Controls NPC/mob spawning.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateCreatureSpawn` | `int evaluateCreatureSpawn(String worldName, int x, int y, int z)` | verdict int (0 or 2 only) |

**Intercepted in 4 locations:**
- `SpawnMarkerEntity` — Spawn marker NPC spawns
- `WorldSpawnJobSystems.trySpawn()` — World chunk spawning
- `NPCPlugin` — NPC entity addition (SPAWN reason only)
- `Store.addEntity()` — Entity loading from save data (LOAD reason only)

**Spawn startup behavior:** See [feature-detection.md](feature-detection.md#spawn-startup-behavior).

---

### Slot 22: `respawn`

Overrides player respawn location. This is a **value hook** — it returns override coordinates instead of a verdict int.

| Method | Signature | Return | Required |
|--------|-----------|--------|----------|
| `evaluateRespawn` | `double[] evaluateRespawn(UUID playerUuid, String worldName, int deathX, int deathY, int deathZ)` | `double[3]` or `null` | Yes |

**Intercepted actions:** Respawn position resolution via `Player.getRespawnPosition()`.

**Parameters:**
- `playerUuid` — The respawning player's UUID
- `worldName` — The world name where the player died
- `deathX, deathY, deathZ` — Approximate death location (from entity transform)

**Return values:**
- `double[3]` with `[x, y, z]` — override the respawn location to these coordinates
- `null` — use default respawn logic (bed, world spawn, etc.)

**Use cases:** Respawn at faction home when dying in claimed territory. Respawn at zone-defined spawn point. Respawn at nearest ally base.

**Note:** This is the only hook besides `interaction_log` that does not use the standard verdict protocol.

---

### Slot 26: `prefab_spawn`

Intercepts prefab entity spawning from save data.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluatePrefabSpawn` | `int evaluatePrefabSpawn(String worldName, int x, int y, int z)` | verdict int (0 or 2 only) |

**Intercepted actions:** Entity addition via `Store.addEntity()` when the reason is `LOAD`. Only fires for entities being loaded from persistent storage, not for freshly spawned entities.

**Note:** No player context — only returns 0 (allow) or 2 (silent deny). Position may be approximate (0,0,0) if entity position is unavailable during load.

**Use cases:** Prevent unwanted entity restoration in protected regions. Block entity loading in purged territories.

---

### Slot 28: `mount`

Intercepts entity mount/ride interactions.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateMount` | `int evaluateMount(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int |
| `fetchMountDenyReason` | `String fetchMountDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null |

**Intercepted actions:** Mount entity interactions via `DamageEntityInteraction.firstRun()`. Position is the mount entity's location.

**Use cases:** Prevent outsiders from riding mounts in claimed territory. Restrict mount usage in safe zones.

---

## Transport

### Slot 9: `teleporter`

Controls teleporter block interaction.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateTeleporter` | `int evaluateTeleporter(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int |
| `fetchTeleporterDenyReason` | `String fetchTeleporterDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null |

**Intercepted actions:** Teleporter block use via `TeleporterInteraction.interactWithBlock()`. Position is the teleporter block.

---

### Slot 10: `portal`

Controls all portal and instance interactions.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateGateway` | `int evaluateGateway(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int |
| `fetchGatewayDenyReason` | `String fetchGatewayDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null |

**Intercepted across 6 interaction types** (via `SimpleBlockInteractionGate` and `SimpleInstantInteractionGate`):
- `EnterPortalInteraction` — Portal entry (position = portal block)
- `ReturnPortalInteraction` — Portal return/exit (position = portal block)
- `TeleportConfigInstanceInteraction` — Configured instance teleport (position = block)
- `TeleportInstanceInteraction` — Instance teleport (position = player)
- `ExitInstanceInteraction` — Instance exit (position = player)
- `HubPortalInteraction` — Creative hub portal (position = player)

---

### Slot 21: `seat`

Intercepts block seating — chairs, benches, and other sittable blocks.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateSeat` | `int evaluateSeat(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int |
| `fetchSeatDenyReason` | `String fetchSeatDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null |

**Intercepted actions:** Seating interaction via `SeatingInteraction.interactWithBlock()`. Position is the seat block.

**Use cases:** Prevent outsiders from sitting on chairs/benches in claimed territory. Restrict seating in safe zones or event areas.

---

## Commands

### Slot 11: `command`

Controls command execution.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateCommand` | `int evaluateCommand(Player player, String command)` | verdict int |
| `fetchCommandDenyReason` | `String fetchCommandDenyReason(Player player, String command)` | deny message or null |

**Intercepted actions:** All player commands via `CommandManager.handleCommand()`. Console commands are not intercepted.

**Note:** The `Player` parameter is a `com.hypixel.hytale.server.core.entity.entities.Player` instance.

---

### Slot 17: `container_open`

Controls storage container opening.

| Method | Signature | Return |
|--------|-----------|--------|
| `evaluateContainerOpen` | `int evaluateContainerOpen(UUID playerUuid, String worldName, int x, int y, int z)` | verdict int |
| `fetchContainerOpenDenyReason` | `String fetchContainerOpenDenyReason(UUID playerUuid, String worldName, int x, int y, int z)` | deny message or null |

**Intercepted actions** (via `SimpleBlockInteractionGate`):
- `OpenContainerInteraction` — Storage container opening
- `OpenProcessingBenchInteraction` — Processing bench opening
- `OpenBenchPageInteraction` — Bench page opening

Position is the container/bench block location.

**Note:** This is separate from `container_access` (slot 7) which controls crafting at workbenches. This hook controls opening storage containers and crafting bench interfaces.

---

## Logging

### Slot 12: `interaction_log`

Controls desync log suppression.

| Method | Signature | Return |
|--------|-----------|--------|
| `isLogFiltered` | `boolean isLogFiltered()` | `true` = suppress, `false` = allow |

**Intercepted in 3 locations:**
- `FloodFillPositionSelector` — Spawn position debug warnings
- `InteractionEntry.setClientState()` — Interaction desync detection
- `InteractionChain.addTempSyncData()` — Sync data ordering warnings

When suppressed, log messages are downgraded from WARNING to FINEST (effectively silent).

**Note:** This is the only hook that returns `boolean` instead of `int` verdicts.
