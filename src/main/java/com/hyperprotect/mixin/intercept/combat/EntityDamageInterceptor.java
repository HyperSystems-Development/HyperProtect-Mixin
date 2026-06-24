package com.hyperprotect.mixin.intercept.combat;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.EntitySnapshot;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.DamageEntityInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
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
 * Intercepts player-initiated entity damage in DamageEntityInteraction.tick0().
 * Covers PvP combat and player-vs-entity damage.
 *
 * <p>Hook contract (entity_damage slot, index 16):
 * <ul>
 *   <li>Primary: {@code int evaluateEntityDamage(UUID attackerUuid, UUID targetUuid, String worldName, int x, int y, int z)}
 *       — targetUuid is null for non-player targets; x/y/z are the target's block position</li>
 *   <li>Secondary: {@code String fetchEntityDamageDenyReason(UUID attackerUuid, UUID targetUuid, String worldName, int x, int y, int z)}
 *       — returns deny reason text sent to the attacker</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES.
 * Fail-open on error.
 */
@Mixin(DamageEntityInteraction.class)
public abstract class EntityDamageInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.entity_damage", "true");
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
            System.err.println("[HyperProtect] EntityDamageInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(16);
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateEntityDamage", EVALUATE_TYPE);
            MethodHandle secondary = null;
            try {
                secondary = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), "fetchEntityDamageDenyReason", FETCH_REASON_TYPE);
            } catch (NoSuchMethodException ignored) {}
            cached = new Object[] { impl, primary, secondary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    @Unique
    private static void formatReason(Object[] hook, PlayerRef playerRef,
                                     UUID attackerUuid, UUID targetUuid, String worldName,
                                     int x, int y, int z) {
        try {
            if (hook.length < 3 || hook[2] == null) return;
            String raw = (String) ((MethodHandle) hook[2]).invoke(hook[0],
                    attackerUuid, targetUuid, worldName, x, y, z);
            if (raw == null || raw.isEmpty()) return;
            Object fmtHandle = getBridge(15);
            if (fmtHandle instanceof MethodHandle mh) {
                Message msg = (Message) mh.invoke(raw);
                playerRef.sendMessage(msg);
            }
        } catch (Throwable t) {
            reportFault(t);
        }
    }

    /**
     * Redirect the context.getTargetEntity() call at the start of tick0.
     * If the hook denies, we set InteractionState.Failed and return null, causing the
     * original code to detect null targetRef and exit via the existing null check.
     * If allowed, we return the original result.
     */
    @Redirect(
        method = "tick0",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/entity/InteractionContext;getTargetEntity()Lcom/hypixel/hytale/component/Ref;",
            ordinal = 0)
    )
    private Ref<EntityStore> gateEntityDamage(InteractionContext context,
                                               boolean firstRun, float time,
                                               @Nonnull InteractionType type,
                                               @Nonnull InteractionContext contextParam,
                                               @Nonnull CooldownHandler cooldownHandler) {
        // Call the original method first to get the real target ref
        Ref<EntityStore> targetRef = context.getTargetEntity();

        try {
            Object[] hook = resolveHook();
            if (hook == null) return targetRef; // No hook = allow

            if (targetRef == null || !targetRef.isValid()) return targetRef; // Let original handle it

            CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
            if (commandBuffer == null) return targetRef;

            Ref<EntityStore> attackerRef = context.getEntity();
            if (attackerRef == null || !attackerRef.isValid()) return targetRef;

            PlayerRef attackerPlayerRef = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
            if (attackerPlayerRef == null) return targetRef; // Not a player attacker — skip

            World world = commandBuffer.getExternalData().getWorld();
            if (world == null) return targetRef;

            UUID attackerUuid = attackerPlayerRef.getUuid();
            String worldName = world.getName();

            // Resolve target UUID (null for non-player targets)
            PlayerRef targetPlayerRef = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
            UUID targetUuid = targetPlayerRef != null ? targetPlayerRef.getUuid() : null;

            // Get target position from entity snapshot
            int x = 0, y = 0, z = 0;
            try {
                EntitySnapshot targetSnapshot = context.getSnapshot(targetRef, commandBuffer);
                if (targetSnapshot != null) {
                    Vector3d pos = targetSnapshot.getPosition();
                    x = (int) pos.x;
                    y = (int) pos.y;
                    z = (int) pos.z;
                }
            } catch (Exception ignored) {
                // Position unavailable — pass (0,0,0), consumer should handle gracefully
            }

            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                    hook[0], attackerUuid, targetUuid, worldName, x, y, z);

            if (verdict < 0) return targetRef; // Fail-open for negative/unknown values

            if (verdict == 1 || verdict == 2 || verdict == 3) {
                if (verdict == 1) {
                    formatReason(hook, attackerPlayerRef, attackerUuid, targetUuid, worldName, x, y, z);
                }
                context.getState().state = InteractionState.Failed;
                return null; // Return null to trigger the existing null-check early exit in tick0
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: allow damage
        }
        return targetRef;
    }
}
