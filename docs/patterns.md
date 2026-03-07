# Integration Patterns

## Fail-Open Principle

All hooks follow fail-open behavior:
- **No hook registered** → action is allowed
- **Hook throws exception** → action is allowed (with sampled warning log via FaultReporter)
- **Hook returns 0 or negative** → action is allowed

This ensures that if the protection mod crashes or hasn't loaded yet, players can still interact with the world normally.

## Verdict Protocol

All hooks (except `interaction_log`) return `int` verdicts:

```java
public int evaluate(UUID playerUuid, String worldName, int x, int y, int z) {
    if (!isProtected(worldName, x, y, z)) return 0;  // ALLOW
    if (!hasBypass(playerUuid)) return 1;              // DENY_WITH_MESSAGE
    return 0;                                          // ALLOW (bypass)
}

public String fetchDenyReason(UUID playerUuid, String worldName, int x, int y, int z) {
    return "&redThis area is protected!";
}
```

| Verdict | Name | Behavior |
|---------|------|----------|
| `0` | ALLOW | Action proceeds |
| `1` | DENY_WITH_MESSAGE | Blocked; mixin calls `fetch*DenyReason()` and sends to player |
| `2` | DENY_SILENT | Blocked, no message sent |
| `3` | DENY_MOD_HANDLES | Blocked, consumer mod sends its own messages |
| negative | ALLOW | Fail-open safety |

## Bypass Pattern

**HyperProtect-Mixin does NOT check bypass permissions.** The mixin layer passes all actions to your hook — your hook implementation decides whether to allow or deny.

```java
public class MyProtectionHook {
    public int evaluate(UUID playerUuid, String worldName, int x, int y, int z) {
        // Your mod handles bypass logic
        if (hasBypass(playerUuid)) {
            return 0; // ALLOW
        }

        if (isProtected(worldName, x, y, z)) {
            return 1; // DENY_WITH_MESSAGE
        }
        return 0; // ALLOW
    }

    public String fetchDenyReason(UUID playerUuid, String worldName, int x, int y, int z) {
        return "&#FF5555This area is protected!";
    }
}
```

This design means:
- Different mods can implement different bypass rules
- No coupling between the mixin layer and any specific permission system
- The hook is the single source of truth for allow/deny decisions

## Bridge Registration

You don't need a compile-time dependency on HyperProtect-Mixin. Register hooks via the shared `AtomicReferenceArray`:

```java
@SuppressWarnings("unchecked")
private void registerHooks() {
    AtomicReferenceArray<Object> bridge = (AtomicReferenceArray<Object>)
        System.getProperties().get("hyperprotect.bridge");

    if (bridge == null) {
        getLogger().warning("HyperProtect-Mixin bridge not found!");
        return;
    }

    bridge.set(0, new BlockBreakHook());      // block_break
    bridge.set(1, new ExplosionHook());       // explosion
    bridge.set(8, new SpawnHook());           // mob_spawn
    // ... register more hooks
}
```

## Message Formatting

Deny messages returned from `fetch*DenyReason()` support `&`-code formatting:

| Code | Effect |
|------|--------|
| `&0`-`&9`, `&a`-`&f` | Standard color codes |
| `&#RRGGBB` | Hex color (e.g., `&#FF5555`) |
| `&#RGB` | Short hex (e.g., `&#F55`) |
| `&red`, `&blue`, etc. | Named colors |
| `&l` | Bold |
| `&o` | Italic |
| `&m` | Monospace |
| `&r` | Reset formatting |

**Available named colors:** black, dark_blue, dark_green, dark_aqua, dark_red, dark_purple, gold, gray, dark_gray, blue, green, aqua, red, light_purple, yellow, white, orange, pink, cyan, brown, lime, magenta

Example:
```java
return "&#FF5555&lProtected! &rYou cannot break blocks in &gold" + regionName;
```

## One Hook Per Slot

Each slot supports only one handler. The last `bridge.set(index, ...)` wins. If two mods register the same slot, the second overwrites the first.

Design your mod to be the single source of truth for each hook you register.

## Thread Safety

Hooks are called from server threads (typically the world thread). Your hook implementations must be thread-safe:

- Use `ConcurrentHashMap` for shared state
- Avoid blocking operations in hook methods
- The same hook may be called concurrently for different worlds

## Unregistering Hooks

To cleanly remove a hook (e.g., on mod disable):

```java
@SuppressWarnings("unchecked")
AtomicReferenceArray<Object> bridge = (AtomicReferenceArray<Object>)
    System.getProperties().get("hyperprotect.bridge");

if (bridge != null) {
    bridge.set(0, null); // Remove block_break hook
}
```

## NPC Role Context

When the `use` hook (slot 20) fires for NPC interactions, HyperProtect-Mixin extracts the NPC's role name via reflection and stores it in a system property:

```java
public int evaluateUse(UUID playerUuid, String worldName, int x, int y, int z) {
    // Read NPC role context (set by SimpleInstantInteractionGate)
    String npcRole = (String) System.getProperties().remove("hyperprotect.context.npc_role");

    if (npcRole != null) {
        // Classify: is this a tameable creature or a shop/quest NPC?
        if (isTameableRole(npcRole)) {
            return checkTamePermission(playerUuid, worldName, x, y, z);
        } else {
            return checkInteractPermission(playerUuid, worldName, x, y, z);
        }
    }
    return 0; // ALLOW
}
```

The `remove()` call atomically reads and clears the context property.

## Block Type Context

When the `use`, `block_place`, `hammer`, or other block interaction hooks fire, the block type at the target position is available via system properties:

```java
public int evaluateUse(UUID playerUuid, String worldName, int x, int y, int z) {
    String blockId = (String) System.getProperties().remove("hyperprotect.context.block_id");
    String blockState = (String) System.getProperties().remove("hyperprotect.context.block_state");

    // Use block type for fine-grained protection decisions
    if ("crafting_bench".equals(blockId)) {
        return checkBenchPermission(playerUuid, worldName, x, y, z);
    }
    return 0; // ALLOW
}
```

## Crafting Context Pattern

The `CraftingResourceFilter` (slot 23) uses a `CraftingContext` bridge class with ThreadLocals to pass crafting bench position and player UUID from `BenchPositionCapture` to the hook:

```java
// CraftingContext stores bench position and player UUID via ThreadLocal
// BenchPositionCapture sets these when a player opens a crafting bench
// CraftingResourceFilter reads them when validating recipes

public int evaluateChestAccess(UUID playerUuid, String worldName,
                                int benchX, int benchY, int benchZ,
                                int benchX2, int benchY2, int benchZ2) {
    // benchX/Y/Z = crafting bench position (from CraftingContext ThreadLocal)
    // playerUuid = crafter's UUID (from CraftingContext ThreadLocal)
    Territory territory = territories.getTerritoryAt(worldName, benchX, benchY, benchZ);
    if (territory == null) return 0;
    if (territory.isMember(playerUuid)) return 0;
    return 1; // Deny non-members from crafting at this bench
}
```
