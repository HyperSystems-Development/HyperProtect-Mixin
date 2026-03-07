# Getting Started with HyperProtect-Mixin

## What is HyperProtect-Mixin?

HyperProtect-Mixin is a generic server protection hook framework for Hytale. It uses Hyxin mixins to inject 29 interceptors providing 27 protection hooks across building, combat, items, entities, containers, transport, commands, and logging.

**Any mod** can register hook handlers to control these actions — no compile-time dependency needed.

## How It Works

1. HyperProtect-Mixin loads as an early plugin (`earlyplugins/`)
2. It creates a shared `AtomicReferenceArray<Object>(30)` stored in `System.getProperties()` under `"hyperprotect.bridge"`
3. Mixins inject protection checkpoints into server code
4. Your mod places hook objects at specific slot indices in the bridge array
5. When a protected action occurs, the mixin calls your handler via cached MethodHandles

## Detecting HyperProtect-Mixin

Check if the mixin plugin is loaded:

```java
boolean loaded = "true".equals(System.getProperty("hyperprotect.bridge.active"));
```

Check individual interceptor availability:

```java
boolean hasBlockBreak = "true".equals(System.getProperty("hyperprotect.intercept.block_break"));
boolean hasPortal = "true".equals(System.getProperty("hyperprotect.intercept.portal_entry"));
```

## Slot Indices

The bridge array uses fixed integer indices for each hook:

| Index | Name | Description |
|-------|------|-------------|
| 0 | `block_break` | Block harvesting and item pickup |
| 1 | `explosion` | Explosion block damage |
| 2 | `fire_spread` | Fire fluid spreading |
| 3 | `builder_tools` | Builder tool paste |
| 4 | `item_pickup` | Proximity item pickup |
| 5 | `death_drop` | Death inventory drops |
| 6 | `durability` | Item durability loss |
| 7 | `container_access` | Crafting/container access |
| 8 | `mob_spawn` | NPC/mob spawning |
| 9 | `teleporter` | Teleporter block use |
| 10 | `portal` | Portal and instance access |
| 11 | `command` | Command execution |
| 12 | `interaction_log` | Desync log suppression |
| 13 | `spawn_ready` | Boolean flag: spawn hook initialized |
| 14 | `spawn_allow_startup` | Boolean flag: allow spawns during startup |
| 15 | `format_handle` | Reserved: cached ChatFormatter MethodHandle |
| 16 | `entity_damage` | Player-initiated entity damage (PvP) |
| 17 | `container_open` | Storage container opening |
| 18 | `block_place` | Block placement |
| 19 | `hammer` | Hammer block cycling (variant rotation) |
| 20 | `use` | Block state changes and interactions |
| 21 | `seat` | Block seating (chairs, benches) |
| 22 | `respawn` | Player respawn location override (value hook) |
| 23 | `crafting_resource` | Crafting resource validation |
| 24 | `map_marker` | Map marker visibility filtering |
| 25 | `fluid_spread` | Non-fire fluid spreading (water, lava) |
| 26 | `prefab_spawn` | Prefab entity spawn gating |
| 27 | `projectile_launch` | Projectile launch interception |
| 28 | `mount` | Mount/ride entity interception |
| 29 | `barter_trade` | Barter/trade NPC interactions |

> **Note:** The bridge array has 30 elements (indices 0-29). Indices 0-29 are named slots. Indices 13, 14, and 15 are utility slots (boolean flags and cached MethodHandle), not consumer hooks.

## Verdict Protocol

All hooks (except boolean ones) return `int` verdicts:

| Value | Meaning | Behavior |
|-------|---------|----------|
| `0` | ALLOW | Action proceeds normally |
| `1` | DENY_WITH_MESSAGE | Action blocked; mixin calls `fetch*DenyReason()` and sends message to player |
| `2` | DENY_SILENT | Action blocked, no message |
| `3` | DENY_MOD_HANDLES | Action blocked, consumer mod handles its own messaging |
| negative/unknown | ALLOW | Fail-open safety |

## Minimal Registration Example

### Step 1: Create a hook handler object

```java
public class MyProtectionHook {
    // Block break hook — return verdict int
    public int evaluate(UUID playerUuid, String worldName, int x, int y, int z) {
        if (isProtectedArea(worldName, x, y, z)) {
            return 1; // DENY_WITH_MESSAGE
        }
        return 0; // ALLOW
    }

    // Called when verdict is 1 (DENY_WITH_MESSAGE)
    public String fetchDenyReason(UUID playerUuid, String worldName, int x, int y, int z) {
        return "&redYou cannot break blocks in this area!";
    }
}
```

### Step 2: Get the bridge array

```java
@SuppressWarnings("unchecked")
AtomicReferenceArray<Object> bridge = (AtomicReferenceArray<Object>)
    System.getProperties().get("hyperprotect.bridge");
```

### Step 3: Register your handler

```java
bridge.set(0, new MyProtectionHook()); // Slot 0 = block_break
```

That's it! The block break interceptor will now call your `evaluate()` method before every block break.

## Next Steps

- **[hooks.md](hooks.md)** — Full hook reference (all 27 hooks with method signatures)
- **[patterns.md](patterns.md)** — Integration patterns (bypass, fail-open, messages)
- **[feature-detection.md](feature-detection.md)** — Checking which interceptors are loaded
- **[examples.md](examples.md)** — Complete working examples
