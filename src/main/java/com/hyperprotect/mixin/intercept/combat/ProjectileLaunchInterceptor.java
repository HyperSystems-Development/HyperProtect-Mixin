package com.hyperprotect.mixin.intercept.combat;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.modules.projectile.ProjectileModule;
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
 * Intercepts projectile spawning via ProjectileModule.spawnProjectile() — slot 27.
 *
 * <p>No event exists for projectile creation. {@code spawnProjectile()} creates arrows,
 * spells, fireballs without any hook. Impact damage is covered by
 * {@link EntityDamageInterceptor}, but launch prevention requires intercepting the
 * spawn call.
 *
 * <p>Hook contract (projectile_launch slot):
 * <pre>
 *   int evaluateProjectileLaunch(UUID player, String world, int x, int y, int z)
 *     Verdict: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT
 * </pre>
 *
 * <p>Fail-open: no hook = allow all launches.
 */
@Mixin(ProjectileModule.class)
public class ProjectileLaunchInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.projectile_launch", "true");
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
            System.err.println("[HyperProtect] ProjectileLaunchInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(27); // projectile_launch
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateProjectileLaunch", EVALUATE_TYPE);
            cached = new Object[] { impl, primary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    /**
     * Gate projectile launches by redirecting the {@code commandBuffer.addEntity(holder, SPAWN)}
     * call inside the 6-arg {@code ProjectileModule.spawnProjectile(UUID, ...)} overload — the
     * point where all launch paths converge to create the projectile entity. Both the 5-arg
     * overload and direct callers (e.g. ProjectileInteraction) funnel through this method, so
     * redirecting the entity creation here catches every launch. Returning null skips entity
     * creation (the projectile is not spawned); the launching method ignores the returned ref
     * for predicted launches, so this cleanly cancels the projectile.
     *
     * <p>The launching player's UUID + world are read from the enclosing method's
     * {@code creatorRef}/{@code commandBuffer}; the spawn position from {@code position}.
     */
    @Redirect(
        method = "spawnProjectile(Ljava/util/UUID;Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/CommandBuffer;Lcom/hypixel/hytale/server/core/modules/projectile/config/ProjectileConfig;Lorg/joml/Vector3d;Lorg/joml/Vector3d;)Lcom/hypixel/hytale/component/Ref;",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/CommandBuffer;addEntity(Lcom/hypixel/hytale/component/Holder;Lcom/hypixel/hytale/component/AddReason;)Lcom/hypixel/hytale/component/Ref;"
        ),
        require = 0
    )
    private Ref<EntityStore> gateProjectileLaunch(
            CommandBuffer<EntityStore> buffer,
            Holder<EntityStore> holder,
            AddReason reason,
            UUID predictionId,
            Ref<EntityStore> creatorRef,
            CommandBuffer<EntityStore> commandBuffer,
            ProjectileConfig config,
            Vector3d position,
            Vector3d direction) {

        try {
            Object[] hook = resolveHook();
            if (hook == null) {
                return buffer.addEntity(holder, reason);
            }

            // Get creator's player UUID + world
            UUID playerUuid = null;
            String worldName = null;
            try {
                if (creatorRef != null && creatorRef.isValid() && commandBuffer != null) {
                    PlayerRef pRef = commandBuffer.getComponent(creatorRef, PlayerRef.getComponentType());
                    if (pRef != null) {
                        playerUuid = pRef.getUuid();
                    }
                    var world = commandBuffer.getExternalData().getWorld();
                    if (world != null) {
                        worldName = world.getName();
                    }
                }
            } catch (Exception ignored) {}

            // Only gate player-launched projectiles
            if (playerUuid == null || worldName == null) {
                return buffer.addEntity(holder, reason);
            }

            int x = (int) position.x;
            int y = (int) position.y;
            int z = (int) position.z;

            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                    hook[0], playerUuid, worldName, x, y, z);

            if (verdict > 0) {
                return null; // DENY — don't create the projectile entity
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: allow launch
        }

        return buffer.addEntity(holder, reason);
    }
}
