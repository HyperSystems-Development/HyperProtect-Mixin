package com.hyperprotect.mixin.intercept.entities;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
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
 * Intercepts NPC/prefab entity loading via Store.addEntity(LOAD) — slot 26.
 *
 * <p>No event exists when entities are loaded from world save data. This is how NPC prefabs,
 * saved entities, and template-spawned entities are restored during chunk load — completely
 * separate from mob spawning events.
 *
 * <p>Hook contract (prefab_spawn slot):
 * <pre>
 *   int evaluatePrefabSpawn(String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW, &gt;0=DENY (prevent entity from loading)
 * </pre>
 *
 * <p>Fail-open: no hook = allow all entity loading.
 */
@Mixin(Store.class)
public class PrefabSpawnInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.prefab_spawn", "true");
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
            System.err.println("[HyperProtect] PrefabSpawnInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(26); // prefab_spawn
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluatePrefabSpawn", EVALUATE_TYPE);
            cached = new Object[] { impl, primary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    /**
     * Redirect addEntity() to intercept entity loading (LOAD reason only).
     * Returns null ref to prevent the entity from being added when denied.
     */
    @Redirect(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/Store;addEntity(Lcom/hypixel/hytale/component/Holder;Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/AddReason;)Lcom/hypixel/hytale/component/Ref;"
        ),
        require = 0
    )
    private static <T> Ref<T> gatePrefabLoad(Store<T> store, Holder<T> holder,
                                              Ref<T> parentRef, AddReason reason) {
        // Only intercept LOAD operations (prefab/save restoration)
        if (reason != AddReason.LOAD) {
            return store.addEntity(holder, parentRef, reason);
        }

        try {
            Object[] hook = resolveHook();
            if (hook == null) {
                return store.addEntity(holder, parentRef, reason);
            }

            // Extract world name via reflection (Store's generic type prevents direct access)
            String worldName = null;
            int x = 0, y = 0, z = 0;
            try {
                var extData = store.getExternalData();
                if (extData != null) {
                    // Use reflection to call getWorld() since Store<T> generic prevents static typing
                    var getWorld = extData.getClass().getMethod("getWorld");
                    Object world = getWorld.invoke(extData);
                    if (world != null) {
                        var getName = world.getClass().getMethod("getName");
                        worldName = (String) getName.invoke(world);
                    }
                }
            } catch (Exception ignored) {}

            if (worldName == null) {
                return store.addEntity(holder, parentRef, reason);
            }

            int verdict = (int) ((MethodHandle) hook[1]).invoke(hook[0], worldName, x, y, z);
            if (verdict > 0) {
                return null; // DENY — prevent entity from loading
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: allow entity loading
        }

        return store.addEntity(holder, parentRef, reason);
    }
}
