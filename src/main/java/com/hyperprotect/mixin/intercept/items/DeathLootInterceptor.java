package com.hyperprotect.mixin.intercept.items;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts death item drops in DeathSystems.DropPlayerDeathItems.
 * When the hook denies, the entire death drop process is cancelled (player keeps items).
 *
 * <p>Hook contract (death_drop slot):
 * <pre>
 *   int evaluateDeathLoot(UUID playerUuid, String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW (drop normally), non-zero=DENY (keep inventory)
 * </pre>
 *
 * <p>No messaging needed — death drops are a background process.
 */
@Mixin(targets = "com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems$DropPlayerDeathItems")
public abstract class DeathLootInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    static {
        System.setProperty("hyperprotect.intercept.death_drop", "true");
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
            System.err.println("[HyperProtect] DeathLootInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(5);
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateDeathLoot",
                MethodType.methodType(int.class,
                        UUID.class, String.class, int.class, int.class, int.class));
            cached = new Object[] { impl, primary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    /**
     * Redirect playerComponent.getGameMode() in onComponentAdded.
     * The original code has: {@code if (playerComponent.getGameMode() == Creative) return;}
     * When the hook denies death drops, we return {@link GameMode#Creative} to trigger
     * that existing early-exit path cleanly — no null returns, no NPE risk.
     *
     * <p>Previous approach redirected store.getComponent() and returned null on deny,
     * but the original code does NOT null-check the result — it immediately calls
     * .getGameMode(), causing an NPE that leaves DeathComponent stuck on the entity.
     */
    @Redirect(
        method = "onComponentAdded",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/entity/entities/Player;getGameMode()Lcom/hypixel/hytale/protocol/GameMode;",
            ordinal = 0)
    )
    private GameMode gateDeathDrops(Player player,
                                    @Nonnull Ref<EntityStore> entityRef,
                                    @Nonnull DeathComponent component,
                                    @Nonnull Store<EntityStore> store,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (player == null) return GameMode.Creative; // defensive — skip drops safely

        try {
            Object[] hook = resolveHook();
            if (hook == null) return player.getGameMode(); // No hook = normal flow

            PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
            if (playerRef == null) return player.getGameMode();

            World world = store.getExternalData().getWorld();
            String worldName = world != null ? world.getName() : null;
            if (worldName == null) return player.getGameMode();

            TransformComponent transform = store.getComponent(entityRef, TransformComponent.getComponentType());
            if (transform == null) return player.getGameMode();

            Vector3d pos = transform.getPosition();
            UUID playerUuid = playerRef.getUuid();

            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                    hook[0], playerUuid, worldName,
                    (int) pos.getX(), (int) pos.getY(), (int) pos.getZ());

            // Verdict 0 = ALLOW (drop normally), anything else = keep inventory
            if (verdict != 0) {
                return GameMode.Creative; // deny → triggers original early-return
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: drop items normally
        }
        return player.getGameMode();
    }
}
