# HyperProtect-Mixin

[![Discord](https://img.shields.io/discord/1327109068498677810?color=7289da&label=Discord&logo=discord&logoColor=white)](https://discord.com/invite/aZaa5vcFYh)
[![GitHub](https://img.shields.io/github/stars/HyperSystems-Development/HyperProtect-Mixin?style=social)](https://github.com/HyperSystems-Development/HyperProtect-Mixin)

Server-level event interception via Hyxin mixins for Hytale. Provides a lock-free bridge API that any mod can use to intercept and control server actions — no compile-time dependency required.

**Version:** 1.2.0
**Platform:** Hytale Early Access
**Type:** Hyxin Early Plugin
**License:** GPLv3

---

## What It Does

HyperProtect-Mixin injects protection checkpoints into the Hytale server at the bytecode level using [Hyxin](https://www.curseforge.com/hytale/mods/hyxin) mixins. It exposes **27 protection hooks** through a 30-slot `AtomicReferenceArray` bridge that any mod can read and write without a compile-time dependency.

When a protected action occurs (block break, explosion, PvP hit, portal use, etc.), the mixin interceptor checks the bridge for a registered hook. If one exists, it calls the hook's `evaluate` method and acts on the verdict.

## Why HyperProtect-Mixin?

| | HyperProtect-Mixin | OrbisGuard-Mixins |
|---|---|---|
| **Hook count** | 27 hooks (29 interceptors) | 27 mixins |
| **Bridge type** | `AtomicReferenceArray` (lock-free reads) | `ConcurrentHashMap` (lock contention) |
| **Safety model** | Fail-open (errors allow actions) | Varies |
| **Bypass handling** | Hook decides (no coupling to permissions) | Mixin checks permissions |
| **Message formatting** | Built-in `&`-code formatter with hex colors | None |
| **Feature detection** | Per-interceptor system properties | Single property |
| **OG coexistence** | Auto-detects OG, disables conflicting mixins | No awareness of other mixin mods |
| **Spawn protection** | Configurable startup blocking | None |

---

## Requirements

- Hytale server (Early Access)
- [Hyxin](https://www.curseforge.com/hytale/mods/hyxin) early plugin loader (v0.0.11+)
- `--accept-early-plugins` flag in server start script

## Installation

1. Create an `earlyplugins/` folder in your server directory (if it doesn't exist)
2. Place `Hyxin.jar` and `HyperProtect-Mixin.jar` in `earlyplugins/` (**NOT** `mods/`)
3. Add `--accept-early-plugins` to your server start script:

**Linux:**
```bash
DEFAULT_ARGS="--accept-early-plugins --assets ../Assets.zip"
```

**Windows:**
```batch
set DEFAULT_ARGS=--accept-early-plugins --assets ../Assets.zip
```

4. Start the server. You should see in the console:
```
HyperProtect-Mixin loaded!
Protection hooks: block-break, block-place, explosion, entity-damage, ...
```

---

## How It Works

```
1. Server starts → Hyxin loads HyperProtect-Mixin as an early plugin
2. Plugin creates AtomicReferenceArray<Object>(30) in System.getProperties()
3. Hyxin injects 29 mixin interceptors into server bytecode
4. Consumer mod (e.g., HyperFactions) places hook objects at slot indices
5. Server event fires → interceptor reads hook from bridge → calls evaluate() → acts on verdict
```

### Verdict Protocol

All hooks return `int` verdicts (except `respawn` which returns `double[]` coordinates):

| Value | Name | Behavior |
|-------|------|----------|
| `0` | ALLOW | Action proceeds normally |
| `1` | DENY_WITH_MESSAGE | Blocked; sends formatted deny message to player |
| `2` | DENY_SILENT | Blocked, no message |
| `3` | DENY_MOD_HANDLES | Blocked, consumer mod handles messaging |
| negative | ALLOW | Fail-open safety |

---

## Hook List

### Building (8 hooks)

| Slot | Name | Description |
|------|------|-------------|
| 0 | `block_break` | Block harvesting and interactive item pickup |
| 1 | `explosion` | Explosion block damage |
| 2 | `fire_spread` | Fire fluid spreading |
| 3 | `builder_tools` | Builder tool paste operations |
| 18 | `block_place` | Block placement |
| 19 | `hammer` | Hammer block cycling (CycleBlockGroupInteraction) |
| 20 | `use` | Block state changes, entity capture, and NPC interactions |
| 25 | `fluid_spread` | Non-fire fluid spreading (water, lava) |

### Items (3 hooks)

| Slot | Name | Description |
|------|------|-------------|
| 4 | `item_pickup` | Proximity item pickup |
| 5 | `death_drop` | Keep-inventory-on-death behavior |
| 6 | `durability` | Item durability loss prevention |

### Containers (4 hooks)

| Slot | Name | Description |
|------|------|-------------|
| 7 | `container_access` | Crafting/workbench access |
| 17 | `container_open` | Storage container opening |
| 23 | `crafting_resource` | Crafting resource validation |
| 29 | `barter_trade` | Barter/trade NPC interactions |

### Combat (2 hooks)

| Slot | Name | Description |
|------|------|-------------|
| 16 | `entity_damage` | Player-initiated damage (PvP and PvE) |
| 27 | `projectile_launch` | Projectile launch interception |

### Entities (4 hooks)

| Slot | Name | Description |
|------|------|-------------|
| 8 | `mob_spawn` | NPC/mob spawning (4 interceptors) |
| 22 | `respawn` | Player respawn location override (value hook) |
| 26 | `prefab_spawn` | Prefab entity spawn gating |
| 28 | `mount` | Mount/ride entity interception |

### Transport (3 hooks)

| Slot | Name | Description |
|------|------|-------------|
| 9 | `teleporter` | Teleporter block use |
| 10 | `portal` | All portal and instance interactions (6 interaction types) |
| 21 | `seat` | Block seating (chairs, benches) |

### Commands & Logging (2 hooks)

| Slot | Name | Description |
|------|------|-------------|
| 11 | `command` | Command execution filtering |
| 12 | `interaction_log` | Desync log suppression (boolean, not verdict) |

### World Map (1 hook)

| Slot | Name | Description |
|------|------|-------------|
| 24 | `map_marker` | Map marker visibility filtering |

### Utility Slots (not hooks)

| Slot | Name | Description |
|------|------|-------------|
| 13 | `spawn_ready` | Boolean flag: spawn hook provider initialized |
| 14 | `spawn_allow_startup` | Boolean flag: allow spawns during startup |
| 15 | `format_handle` | Cached ChatFormatter MethodHandle |

---

## For Consumers (Mod Developers)

You do **not** need a compile-time dependency. Register hooks via the shared bridge:

```java
// Check if HyperProtect-Mixin is loaded
if (!"true".equals(System.getProperty("hyperprotect.bridge.active"))) {
    getLogger().warning("HyperProtect-Mixin not found!");
    return;
}

// Get the bridge array
@SuppressWarnings("unchecked")
AtomicReferenceArray<Object> bridge = (AtomicReferenceArray<Object>)
    System.getProperties().get("hyperprotect.bridge");

// Register a block break hook at slot 0
bridge.set(0, new Object() {
    public int evaluate(UUID playerUuid, String worldName, int x, int y, int z) {
        if (isProtected(worldName, x, y, z)) return 1; // DENY_WITH_MESSAGE
        return 0; // ALLOW
    }

    public String fetchDenyReason(UUID playerUuid, String worldName, int x, int y, int z) {
        return "&#FF5555You cannot break blocks here!";
    }
});
```

See [docs/hooks.md](docs/hooks.md) for complete method signatures for all 27 hooks.

---

## Configuration

HyperProtect-Mixin requires **zero configuration**. It auto-detects and initializes on server startup.

### System Properties

| Property | Value | Purpose |
|----------|-------|---------|
| `hyperprotect.bridge.active` | `"true"` | Bridge initialized |
| `hyperprotect.bridge.version` | `"1.2.0"` | Bridge version |
| `hyperprotect.mode` | `"standalone"` or `"compatible"` | Operating mode (compatible when OrbisGuard-Mixins detected) |
| `hyperprotect.intercept.*` | `"true"` | Per-interceptor load confirmation |

See [docs/feature-detection.md](docs/feature-detection.md) for the full list.

---

## Building from Source

### Requirements

- Java 25+
- Gradle 9.3+ (wrapper included)

```bash
./gradlew jar
```

Output: `build/libs/HyperProtect-Mixin-1.2.0.jar`

---

## Documentation

| Document | Description |
|----------|-------------|
| [Getting Started](docs/getting-started.md) | Quick start guide with minimal registration example |
| [Hook Reference](docs/hooks.md) | All 27 hooks with method signatures and details |
| [Integration Patterns](docs/patterns.md) | Fail-open, verdicts, bypass, threading, messages |
| [Code Examples](docs/examples.md) | Complete working examples for common use cases |
| [Feature Detection](docs/feature-detection.md) | System properties and spawn startup behavior |
| [Migration from OrbisGuard](docs/migration-from-orbisguard.md) | Step-by-step migration guide |

---

## Links

- [HyperSystems Website](https://hypersystems.dev)
- [GitHub Repository](https://github.com/HyperSystems-Development/HyperProtect-Mixin)
- [Issue Tracker](https://github.com/HyperSystems-Development/HyperProtect-Mixin/issues)
- [Discord](https://discord.com/invite/aZaa5vcFYh)
- [HyperFactions](https://github.com/HyperSystems-Development/HyperFactions) (primary consumer)

---

*Part of the [HyperSystems](https://github.com/HyperSystems-Development) plugin suite — built for Hytale, open source, and actively maintained.*
