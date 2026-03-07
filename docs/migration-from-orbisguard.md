# Migration Guide: OrbisGuard-Mixins → HyperProtect-Mixin

This guide covers migrating your mod from OrbisGuard-Mixins to HyperProtect-Mixin.

## Key Differences

| Aspect | OrbisGuard-Mixins | HyperProtect-Mixin |
|--------|-------------------|-------------------|
| Registry type | `ConcurrentHashMap<String, Object>` | `AtomicReferenceArray<Object>(24)` |
| Registry key | `orbisguard.hook.registry` | `hyperprotect.bridge` |
| Hook addressing | String keys | Integer slot indices |
| Return type | `String` (null=allow, non-null=deny) or `boolean` | `int` verdict (0=allow, 1=deny+message, 2=silent deny, 3=mod handles) |
| Bypass permissions | Checked in mixin layer | **Not checked** — your hook decides |
| Loaded property | `orbisguard.mixins.loaded` | `hyperprotect.bridge.active` |
| Classloader property | `orbisguard.mixins.classloader` | `hyperprotect.bridge.loader` |
| Dual registry fallback | Yes (checked both registries) | No (single array only) |
| Interface contracts | Ships interface classes | Reflection-only contracts |
| Error handling | One-shot AtomicBoolean | Sampled FaultReporter (logs every 100th) |
| `hasBypass` parameter | Passed to portal/teleporter hooks | Removed — hook queries permissions |

## Slot Index Reference

| Old OrbisGuard Key | Slot Index | New Method |
|-------------------|------------|------------|
| `orbisguard.harvest.hook` | 0 (`block_break`) | `evaluate(UUID,String,int,int,int)→int` |
| `orbisguard.explosion.hook` | 1 (`explosion`) | `evaluateExplosion(World,int,int,int)→int` |
| `orbisguard.fire.hook` | 2 (`fire_spread`) | `evaluateFlame(String,int,int,int)→int` |
| `orbisguard.buildertools.hook` | 3 (`builder_tools`) | `evaluatePaste(UUID,String,int,int,int)→int` |
| `orbisguard.pickup.hook` | 4 (`item_pickup`) | `evaluate(UUID,String,double,double,double)→int` |
| `orbisguard.death.hook` | 5 (`death_drop`) | `evaluateDeathLoot(UUID,String,int,int,int)→int` |
| `orbisguard.durability.hook` | 6 (`durability`) | `evaluateWear(UUID,String,int,int,int)→int` |
| `orbisguard.workbench.hook` | 7 (`container_access`) | `evaluateCrafting(UUID,String,int,int,int)→int` |
| `orbisguard.spawn.hook` | 8 (`mob_spawn`) | `evaluateCreatureSpawn(String,int,int,int)→int` |
| `orbisguard.teleporter.hook` | 9 (`teleporter`) | `evaluateTeleporter(UUID,String,int,int,int)→int` |
| `orbisguard.portal.hook` | 10 (`portal`) | `evaluateGateway(UUID,String,int,int,int)→int` |
| `orbisguard.command.hook` | 11 (`command`) | `evaluateCommand(Player,String)→int` |
| *(new)* | 16 (`entity_damage`) | `evaluateEntityDamage(UUID,UUID,String,int,int,int)→int` |
| *(new)* | 17 (`container_open`) | `evaluateContainerOpen(UUID,String,int,int,int)→int` |
| *(new)* | 18 (`block_place`) | `evaluateBlockPlace(UUID,String,int,int,int)→int` |
| `orbisguard.use.hook` | 20 (`use`) | `evaluateUse(UUID,String,int,int,int)->int` |
| *(new)* | 23 (`crafting_resource`) | `evaluateChestAccess(UUID,String,int,int,int,int,int,int)→int` |
| *(new)* | 24 (`map_marker`) | `evaluateMapMarker(UUID,String,String,int,int,int)→int` |
| *(new)* | 25 (`fluid_spread`) | `evaluateFluidSpread(String,int,int,int)→int` |
| *(new)* | 26 (`prefab_spawn`) | `evaluatePrefabSpawn(String,int,int,int)→int` |
| *(new)* | 27 (`projectile_launch`) | `evaluateProjectileLaunch(UUID,String,int,int,int)→int` |
| *(new)* | 28 (`mount`) | `evaluateMount(UUID,String,int,int,int)→int` |
| *(new)* | 29 (`barter_trade`) | `evaluateBarterTrade(UUID,String,int,int,int)→int` |

## Registry Migration

### Before (OrbisGuard):
```java
Map<String, Object> registry = (Map<String, Object>) System.getProperties()
    .get("orbisguard.hook.registry");
if (registry == null) {
    registry = new ConcurrentHashMap<>();
    System.getProperties().put("orbisguard.hook.registry", registry);
}
registry.put("orbisguard.harvest.hook", myHarvestHook);
registry.put("orbisguard.portal.hook", myPortalHook);
```

### After (HyperProtect):
```java
@SuppressWarnings("unchecked")
AtomicReferenceArray<Object> bridge = (AtomicReferenceArray<Object>)
    System.getProperties().get("hyperprotect.bridge");

bridge.set(0, myBlockBreakHook);   // block_break
bridge.set(10, myPortalHook);      // portal
```

## Return Type Migration

### Before (OrbisGuard — String return):
```java
public String check(UUID playerUuid, String worldName, int x, int y, int z) {
    if (isProtected(worldName, x, y, z)) {
        return "&redProtected!"; // Non-null = deny + message
    }
    return null; // Allow
}
```

### After (HyperProtect — int verdict + separate deny reason):
```java
public int evaluate(UUID playerUuid, String worldName, int x, int y, int z) {
    if (isProtected(worldName, x, y, z)) {
        return 1; // DENY_WITH_MESSAGE
    }
    return 0; // ALLOW
}

public String fetchDenyReason(UUID playerUuid, String worldName, int x, int y, int z) {
    return "&redProtected!";
}
```

### Before (OrbisGuard — boolean return):
```java
public boolean shouldBlockExplosion(World world, int x, int y, int z) {
    return isProtected(world.getName(), x, y, z);
}
```

### After (HyperProtect — int verdict):
```java
public int evaluateExplosion(World world, int x, int y, int z) {
    return isProtected(world.getName(), x, y, z) ? 2 : 0;
}
```

## Portal / Teleporter Hook Migration

### Before (OrbisGuard — with hasBypass):
```java
public String checkPortal(UUID playerUuid, String worldName, int x, int y, int z, boolean hasBypass) {
    if (hasBypass) return null;
    if (isProtected(worldName, x, y, z)) return "&redCannot use portal!";
    return null;
}
```

### After (HyperProtect — no hasBypass, verdict return):
```java
public int evaluateGateway(UUID playerUuid, String worldName, int x, int y, int z) {
    if (hasBypass(playerUuid)) return 0;          // Check bypass yourself
    if (isProtected(worldName, x, y, z)) return 1;
    return 0;
}

public String fetchGatewayDenyReason(UUID playerUuid, String worldName, int x, int y, int z) {
    return "&redCannot use portal!";
}
```

## Detection Migration

### Before:
```java
if ("true".equals(System.getProperty("orbisguard.mixins.loaded"))) {
    // register hooks
}
```

### After:
```java
if ("true".equals(System.getProperty("hyperprotect.bridge.active"))) {
    // register hooks
}
```

## Spawn State Migration

### Before:
```java
registry.put("orbisguard.spawn.ready", true);
registry.put("orbisguard.spawn.allow_startup", true);
```

### After:
```java
bridge.set(13, Boolean.TRUE);  // spawn_ready
bridge.set(14, Boolean.TRUE);  // spawn_allow_startup
```

## Bypass Permission Migration

**This is the biggest behavioral change.** OrbisGuard checked `hyperprotection.bypass` (and sometimes `orbisguard.bypass`) inside the mixin layer before calling your hook. HyperProtect-Mixin does NOT check any permissions.

### Before (OrbisGuard behavior):
```java
// Mixin layer checked bypass BEFORE calling your hook
// Your hook only received non-bypass players
public String check(UUID playerUuid, String worldName, int x, int y, int z) {
    if (isProtected(worldName, x, y, z)) {
        return "Protected!";
    }
    return null;
}
```

### After (HyperProtect behavior):
```java
// ALL players reach your hook — you decide bypass logic
public int evaluate(UUID playerUuid, String worldName, int x, int y, int z) {
    if (hasPermission(playerUuid, "mymod.bypass.build")) {
        return 0; // Bypass — allow
    }
    if (isProtected(worldName, x, y, z)) {
        return 1; // Deny with message
    }
    return 0; // Allow
}
```

## Classloader Access Migration

### Before:
```java
ClassLoader loader = (ClassLoader) System.getProperties().get("orbisguard.mixins.classloader");
```

### After:
```java
ClassLoader loader = (ClassLoader) System.getProperties().get("hyperprotect.bridge.loader");
```

## Interface Class Migration

OrbisGuard shipped interface classes (e.g., `CommandProtectionHook`) that hook implementations could implement. HyperProtect-Mixin uses **reflection-only contracts** — no interfaces shipped.

Your hook objects just need to have methods with the right names and signatures. No `implements` clause needed.

## New HPM-Only Hooks (No OrbisGuard Equivalent)

HyperProtect-Mixin v1.2.0 provides these hooks that have no OrbisGuard counterpart:
- `crafting_resource` (23) — crafting resource validation at recipe level
- `map_marker` (24) — per-player map marker visibility filtering
- `fluid_spread` (25) — non-fire fluid spread blocking (water/lava)
- `prefab_spawn` (26) — prefab entity spawn gating
- `projectile_launch` (27) — projectile launch interception
- `mount` (28) — mount/ride entity interception
- `barter_trade` (29) — barter/trade NPC interaction filtering
- `block_place` (18) — block placement interception
- `entity_damage` (16) — player-initiated entity damage (PvP/PvE)
- `container_open` (17) — storage container opening
- `seat` (21) — block seating interception
- `capture_crate` (20) — entity capture via capture crate
- `respawn` (22) — player respawn location override

## Checklist

- [ ] Replace `orbisguard.hook.registry` → `hyperprotect.bridge` (and change to `AtomicReferenceArray`)
- [ ] Replace all string key `registry.put()` → integer slot `bridge.set()` (see table above)
- [ ] Convert all `String` return hooks to `int` verdict + `fetch*DenyReason()`
- [ ] Convert all `boolean` return hooks to `int` verdict (true→2, false→0)
- [ ] Replace `orbisguard.mixins.loaded` → `hyperprotect.bridge.active`
- [ ] Replace `orbisguard.mixins.classloader` → `hyperprotect.bridge.loader`
- [ ] Replace `orbisguard.spawn.ready` → `bridge.set(13, Boolean.TRUE)`
- [ ] Replace `orbisguard.spawn.allow_startup` → `bridge.set(14, Boolean.TRUE)`
- [ ] Remove `boolean hasBypass` parameter from portal/teleporter hooks
- [ ] Add bypass permission checks inside your hook implementations
- [ ] Remove any `implements` clauses for OrbisGuard interfaces
- [ ] Deploy HyperProtect-Mixin.jar to `earlyplugins/` (replacing OrbisGuard-Mixins.jar)
- [ ] Remove OrbisGuard-Mixins.jar from `earlyplugins/`
