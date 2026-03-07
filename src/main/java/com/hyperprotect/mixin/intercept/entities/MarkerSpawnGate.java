package com.hyperprotect.mixin.intercept.entities;

import com.hypixel.hytale.server.spawning.SpawnTestResult;
import com.hypixel.hytale.server.spawning.SpawningContext;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts spawn marker entity spawn attempts.
 * Redirects canSpawn() calls on SpawningContext to check the mob_spawn hook.
 *
 * <p>Hook contract (mob_spawn slot):
 * <pre>
 *   int evaluateCreatureSpawn(String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW, 2=DENY_SILENT
 * </pre>
 *
 * <p>Spawn startup behavior:
 * <ul>
 *   <li>If no hook AND not initialized AND startup pass disabled: block spawns</li>
 *   <li>If no hook AND initialized (or startup pass enabled): allow spawns</li>
 * </ul>
 */
@Mixin(SpawnMarkerEntity.class)
public class MarkerSpawnGate {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType SPAWN_EVAL_TYPE = MethodType.methodType(
            int.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType SPAWN_TYPED_TYPE = MethodType.methodType(
            int.class, String.class, String.class, int.class, int.class, int.class);

    @Unique
    private static volatile MethodHandle typedHandle;

    @Unique
    private static volatile boolean typedHandleChecked;

    static {
        System.setProperty("hyperprotect.intercept.spawn_marker", "true");
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static Object getBridge(int slot) {
        try {
            Object bridge = System.getProperties().get("hyperprotect.bridge");
            if (bridge == null) return null;
            return ((AtomicReferenceArray<Object>) bridge).get(slot);
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private static void reportFault(Throwable t) {
        long count = faultCount.incrementAndGet();
        if (count == 1 || count % 100 == 0) {
            System.err.println("[HyperProtect] MarkerSpawnGate error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    /**
     * Redirect canSpawn() on SpawningContext to evaluate the mob_spawn hook.
     * If spawning should be blocked, returns FAIL_NOT_SPAWNABLE.
     */
    @Redirect(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/spawning/SpawningContext;canSpawn()Lcom/hypixel/hytale/server/spawning/SpawnTestResult;"
        ),
        require = 0
    )
    private SpawnTestResult gateCreatureSpawn(SpawningContext context) {
        SpawnTestResult original = context.canSpawn();

        // Only intercept if the server would allow the spawn
        if (original != SpawnTestResult.TEST_OK) {
            return original;
        }

        try {
            int verdict = querySpawnVerdict(context);
            if (verdict != 0) {
                return SpawnTestResult.FAIL_NOT_SPAWNABLE;
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: allow spawn
        }

        return original;
    }

    /**
     * Evaluates whether a creature spawn at the given context should be allowed.
     *
     * @return verdict int: 0=ALLOW, non-zero=DENY
     */
    @Unique
    private static int querySpawnVerdict(SpawningContext context) throws Throwable {
        Object[] cached = hookCache;

        // Re-resolve if hook changed or not yet cached
        Object current = getBridge(8);
        if (cached == null || cached[0] != current) {
            if (current == null) {
                hookCache = null;
                // No hook: check startup behavior
                if (!isSpawnInitialized() && !isStartupPassEnabled()) {
                    return 2; // DENY_SILENT — block spawns until ready
                }
                return 0;
            }
            try {
                MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                    current.getClass(), "evaluateCreatureSpawn", SPAWN_EVAL_TYPE);
                cached = new Object[] { current, primary };
                hookCache = cached;
            } catch (Exception e) {
                reportFault(e);
                return 0;
            }
        }

        String worldName = context.world != null ? context.world.getName() : null;
        if (worldName == null) return 0;

        int x = (int) context.xSpawn;
        int y = (int) context.ySpawn;
        int z = (int) context.zSpawn;

        // Try enhanced typed evaluation (new HF with mob type support)
        String npcType = extractNpcType(context);
        if (npcType != null && !typedHandleChecked) {
            try {
                typedHandle = MethodHandles.publicLookup().findVirtual(
                    cached[0].getClass(), "evaluateCreatureSpawnTyped", SPAWN_TYPED_TYPE);
            } catch (NoSuchMethodException e) {
                // Old HF — no typed method, use position-only
            } catch (Exception e) {
                reportFault(e);
            }
            typedHandleChecked = true;
        }

        int verdict;
        if (npcType != null && typedHandle != null) {
            verdict = (int) typedHandle.invoke(cached[0], worldName, npcType, x, y, z);
        } else {
            verdict = (int) ((MethodHandle) cached[1]).invoke(cached[0], worldName, x, y, z);
        }

        // Fail-open for negative/unknown values
        return verdict < 0 ? 0 : verdict;
    }

    /**
     * Extracts the NPC type identifier from a SpawningContext.
     * Uses the spawnable's class simple name as the type identifier.
     *
     * @return type name (e.g. "ZombieSoldier"), or null if unavailable
     */
    @Unique
    private static String extractNpcType(SpawningContext context) {
        try {
            var field = context.getClass().getDeclaredField("spawnable");
            field.setAccessible(true);
            Object spawnable = field.get(context);
            if (spawnable != null) {
                return spawnable.getClass().getSimpleName();
            }
        } catch (NoSuchFieldException ignored) {
            try {
                var field = context.getClass().getField("spawnable");
                Object spawnable = field.get(context);
                if (spawnable != null) {
                    return spawnable.getClass().getSimpleName();
                }
            } catch (Exception ignored2) {}
        } catch (Exception ignored) {}
        return null;
    }

    @Unique
    private static boolean isSpawnInitialized() {
        return Boolean.TRUE.equals(getBridge(13));
    }

    @Unique
    private static boolean isStartupPassEnabled() {
        return Boolean.TRUE.equals(getBridge(14));
    }
}
