package com.hyperprotect.mixin.intercept.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialStructure;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerItemEntityPickupSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts automatic item pickup in {@code PlayerItemEntityPickupSystem.tick()}.
 *
 * <p>Uses System.properties bridge + MethodHandle for cross-classloader hook resolution.
 *
 * <p>Hook contract (item_pickup slot):
 * <ul>
 *   <li>Primary: {@code int evaluate(UUID, String, double, double, double)} -- returns verdict</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW, 2=DENY (always silent for automatic pickup, no messaging).
 * Negative/unknown values = ALLOW (fail-open).
 */
@Mixin(PlayerItemEntityPickupSystem.class)
public abstract class ProximityLootInterceptor {

    // --- Verdict constants ---

    @Unique private static final int ALLOW = 0;

    // --- Fault tracking ---

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    // --- ThreadLocal state for capturing data across injection points ---

    @Unique
    private static final ThreadLocal<Vector3d> lootPosition = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<Store<EntityStore>> activeStore = new ThreadLocal<>();

    // --- Cached hook (volatile for cross-thread visibility) ---

    @Unique
    private static volatile Object[] hookCache;

    // --- MethodType for hook resolution ---

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, double.class, double.class, double.class);

    static {
        System.setProperty("hyperprotect.intercept.item_pickup", "true");
    }

    // --- Helper methods ---

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
            System.err.println("[HyperProtect] ProximityLootInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    // --- Hook resolution ---

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(4);
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluate", EVALUATE_TYPE);
            cached = new Object[] { impl, primary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    // --- Injection points ---

    /**
     * Capture the store reference for later use.
     */
    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/component/Store;getResource(Lcom/hypixel/hytale/component/ResourceType;)Lcom/hypixel/hytale/component/Resource;")
    )
    private <R extends Resource<EntityStore>> R captureStore(Store<EntityStore> store, ResourceType<EntityStore, R> resourceType) {
        activeStore.set(store);
        return store.getResource(resourceType);
    }

    /**
     * Capture the item entity's position.
     */
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/modules/entity/component/TransformComponent;getPosition()Lorg/joml/Vector3d;",
            ordinal = 0
        )
    )
    private Vector3d captureLootPosition(TransformComponent transformComponent) {
        Vector3d position = transformComponent.getPosition();
        lootPosition.set(position);
        return position;
    }

    /**
     * Evaluate pickup permission when finding the closest player.
     * Returns null to cancel the pickup if denied.
     * No bypass checks -- the hook implementation decides.
     */
    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/component/spatial/SpatialStructure;closest(Lorg/joml/Vector3d;)Ljava/lang/Object;")
    )
    private Object gateAction(SpatialStructure<?> spatialStructure, Vector3d position) {
        Object result = spatialStructure.closest(position);
        if (result == null) {
            return null;
        }

        Vector3d itemPos = lootPosition.get();
        if (itemPos == null) {
            return result;
        }

        try {
            Store<EntityStore> store = activeStore.get();
            if (store == null) {
                return result;
            }

            @SuppressWarnings("unchecked")
            Ref<EntityStore> targetRef = (Ref<EntityStore>) result;
            PlayerRef playerRef = (PlayerRef) store.getComponent(targetRef, PlayerRef.getComponentType());
            if (playerRef == null) {
                return result;
            }

            String worldName = null;
            if (store.getExternalData() != null && ((EntityStore) store.getExternalData()).getWorld() != null) {
                worldName = ((EntityStore) store.getExternalData()).getWorld().getName();
            }
            if (worldName == null) {
                return result;
            }

            Object[] hook = resolveHook();
            if (hook == null) {
                return result; // No hook = allow (fail-open)
            }

            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                    hook[0], playerRef.getUuid(), worldName,
                    itemPos.x(), itemPos.y(), itemPos.z());

            // Any non-zero positive verdict = deny (silent, no messaging for auto pickup)
            if (verdict > ALLOW) {
                return null; // Cancel pickup
            }
        } catch (Throwable e) {
            reportFault(e);
            // Fail-open: allow pickup on error
        }

        return result;
    }
}
