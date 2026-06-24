package com.hyperprotect.mixin.intercept.entities;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Transform;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts player respawn position resolution in Player.getRespawnPosition.
 *
 * <p>This is a <b>value hook</b>, not a gate hook. Instead of returning a verdict,
 * it returns override coordinates for the respawn location.
 *
 * <p>Uses three {@code @Redirect} points to cover all return paths in getRespawnPosition
 * without depending on {@code CallbackInfo} (not on TransformingClassLoader classpath).
 *
 * <p>Hook contract (respawn slot, index 22):
 * <ul>
 *   <li>Primary: {@code double[] evaluateRespawn(UUID, String, int, int, int)} —
 *       returns {@code double[3]} with [x, y, z] to override respawn location,
 *       or {@code null} to use default respawn logic</li>
 * </ul>
 *
 * <p>Fail-open on error (uses default respawn location).
 */
@Mixin(Player.class)
public class RespawnInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            double[].class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final ThreadLocal<Transform> respawnOverride = new ThreadLocal<>();

    static {
        System.setProperty("hyperprotect.intercept.respawn", "true");
    }

    @Shadow
    private static CompletableFuture<Transform> tryUseSpawnPoint(
            World world, List<PlayerRespawnPointData> sortedRespawnPoints,
            int index, Ref<EntityStore> ref, PlayerRef playerComponent, Box boundingBox) {
        throw new AssertionError();
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
            System.err.println("[HyperProtect] RespawnInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(22);
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateRespawn", EVALUATE_TYPE);
            cached = new Object[] { impl, primary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    // --- Redirect 1: Check hook when getting Player component (first getComponent call) ---

    /**
     * Intercepts the first componentAccessor.getComponent() call in getRespawnPosition
     * to evaluate the respawn hook. If the hook returns override coordinates, stores
     * them in a ThreadLocal for the return-path redirects to pick up.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(
        method = "getRespawnPosition",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/ComponentAccessor;getComponent(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentType;)Lcom/hypixel/hytale/component/Component;",
            ordinal = 0)
    )
    private static Component checkRespawnHook(ComponentAccessor ca, Ref ref, ComponentType type) {
        Component result = (Component) ca.getComponent(ref, type);
        if (result == null) return null;

        try {
            Object[] hook = resolveHook();
            if (hook == null) return result;

            ComponentAccessor<EntityStore> typedCa = (ComponentAccessor<EntityStore>) ca;
            Ref<EntityStore> typedRef = (Ref<EntityStore>) ref;

            PlayerRef playerRef = typedCa.getComponent(typedRef, PlayerRef.getComponentType());
            if (playerRef == null) return result;

            UUID playerUuid = playerRef.getUuid();

            // Get world name from component accessor
            World world = ((EntityStore) typedCa.getExternalData()).getWorld();
            String worldName = world != null ? world.getName() : null;
            if (worldName == null) return result;

            // Get death position for context
            int deathX = 0, deathY = 0, deathZ = 0;
            TransformComponent transform = typedCa.getComponent(typedRef, TransformComponent.getComponentType());
            if (transform != null) {
                Vector3d pos = transform.getPosition();
                deathX = (int) pos.x();
                deathY = (int) pos.y();
                deathZ = (int) pos.z();
            }

            double[] override = (double[]) ((MethodHandle) hook[1]).invoke(
                    hook[0], playerUuid, worldName, deathX, deathY, deathZ);

            if (override != null && override.length >= 3) {
                respawnOverride.set(new Transform(override[0], override[1], override[2]));
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: use default respawn
        }
        return result;
    }

    // --- Redirect 2: Intercept CompletableFuture.completedFuture() (covers paths 1 and 2) ---

    /**
     * Intercepts CompletableFuture.completedFuture() calls in getRespawnPosition.
     * If an override was stored by redirect 1, returns a completed future with the
     * override coordinates instead of the original spawn point.
     */
    @SuppressWarnings("unchecked")
    @Redirect(
        method = "getRespawnPosition",
        at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;completedFuture(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;")
    )
    private static CompletableFuture<Transform> interceptCompletedFuture(Object value) {
        Transform override = respawnOverride.get();
        if (override != null) {
            respawnOverride.remove();
            return CompletableFuture.completedFuture(override);
        }
        return CompletableFuture.completedFuture((Transform) value);
    }

    // --- Redirect 3: Intercept tryUseSpawnPoint() (covers path 3 — normal flow) ---

    /**
     * Intercepts Player.tryUseSpawnPoint() in getRespawnPosition.
     * If an override was stored by redirect 1, returns a completed future with the
     * override coordinates instead of running the normal spawn point validation.
     */
    @SuppressWarnings("unchecked")
    @Redirect(
        method = "getRespawnPosition",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/entity/entities/Player;tryUseSpawnPoint(Lcom/hypixel/hytale/server/core/universe/world/World;Ljava/util/List;ILcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/server/core/universe/PlayerRef;Lcom/hypixel/hytale/math/shape/Box;)Ljava/util/concurrent/CompletableFuture;")
    )
    private static CompletableFuture<Transform> interceptTryUseSpawnPoint(
            World world, List<PlayerRespawnPointData> sortedRespawnPoints,
            int index, Ref<EntityStore> ref, PlayerRef playerComponent, Box boundingBox) {
        Transform override = respawnOverride.get();
        if (override != null) {
            respawnOverride.remove();
            return CompletableFuture.completedFuture(override);
        }
        return tryUseSpawnPoint(world, sortedRespawnPoints, index, ref, playerComponent, boundingBox);
    }
}
