package com.hyperprotect.mixin.intercept.entities;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.EntitySnapshot;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.builtin.mounts.interactions.MountInteraction;
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
 * Intercepts mount interactions in MountInteraction.firstRun() — slot 28.
 *
 * <p>No mount/dismount events exist. Players can mount any rideable entity
 * without permission checks.
 *
 * <p>Hook contract (mount slot):
 * <pre>
 *   int evaluateMount(UUID player, String world, int x, int y, int z)
 *     Verdict: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT
 * </pre>
 *
 * <p>Fail-open: no hook = allow all mounting.
 */
@Mixin(MountInteraction.class)
public abstract class MountInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.mount", "true");
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
            System.err.println("[HyperProtect] MountInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(28); // mount
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateMount", EVALUATE_TYPE);
            MethodHandle secondary = null;
            try {
                secondary = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), "fetchMountDenyReason", FETCH_REASON_TYPE);
            } catch (NoSuchMethodException ignored) {}
            cached = new Object[] { impl, primary, secondary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    /**
     * Redirect getTargetEntity() in firstRun to gate mount interactions.
     * Returns null to prevent mounting (existing code handles null targetRef gracefully).
     */
    @Redirect(
        method = "firstRun",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/entity/InteractionContext;getTargetEntity()Lcom/hypixel/hytale/component/Ref;",
            ordinal = 0
        ),
        require = 0
    )
    private Ref<EntityStore> gateMount(InteractionContext context,
                                        @Nonnull InteractionType type,
                                        @Nonnull InteractionContext contextParam,
                                        @Nonnull CooldownHandler cooldownHandler) {
        Ref<EntityStore> targetRef = context.getTargetEntity();

        try {
            Object[] hook = resolveHook();
            if (hook == null) return targetRef;

            if (targetRef == null || !targetRef.isValid()) return targetRef;

            CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
            if (commandBuffer == null) return targetRef;

            Ref<EntityStore> playerRef = context.getEntity();
            if (playerRef == null || !playerRef.isValid()) return targetRef;

            PlayerRef pRef = commandBuffer.getComponent(playerRef, PlayerRef.getComponentType());
            if (pRef == null) return targetRef; // Not a player

            World world = commandBuffer.getExternalData().getWorld();
            if (world == null) return targetRef;

            UUID playerUuid = pRef.getUuid();
            String worldName = world.getName();

            // Get target (mount) position
            int x = 0, y = 0, z = 0;
            try {
                EntitySnapshot snapshot = context.getSnapshot(targetRef, commandBuffer);
                if (snapshot != null) {
                    Vector3d pos = snapshot.getPosition();
                    x = (int) pos.x;
                    y = (int) pos.y;
                    z = (int) pos.z;
                }
            } catch (Exception ignored) {}

            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                    hook[0], playerUuid, worldName, x, y, z);

            if (verdict < 0) return targetRef; // Fail-open

            if (verdict == 1 || verdict == 2) {
                if (verdict == 1 && hook.length > 2 && hook[2] != null) {
                    try {
                        String reason = (String) ((MethodHandle) hook[2]).invoke(
                                hook[0], playerUuid, worldName, x, y, z);
                        if (reason != null && !reason.isEmpty()) {
                            Object fmtHandle = getBridge(15);
                            if (fmtHandle instanceof MethodHandle mh) {
                                Player player = commandBuffer.getComponent(playerRef, Player.getComponentType());
                                if (player != null) {
                                    Message msg = (Message) mh.invoke(reason);
                                    player.sendMessage(msg);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                context.getState().state = InteractionState.Failed;
                return null;
            }
        } catch (Throwable t) {
            reportFault(t);
        }

        return targetRef;
    }
}
