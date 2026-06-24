package com.hyperprotect.mixin.intercept.interaction;

import com.hypixel.hytale.builtin.adventure.farming.interactions.UseCaptureCrateInteraction;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
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
 * Intercepts entity capture in UseCaptureCrateInteraction.
 *
 * <p>UseCaptureCrateInteraction overrides tick0() and handles entity capture
 * (picking up animals with an empty crate) directly, returning WITHOUT calling
 * super.tick0(). This means SimpleBlockInteractionGate never fires for the
 * capture path — only for the release path (placing captured animals).
 *
 * <p>This mixin redirects the {@code context.getTargetEntity()} call inside
 * tick0(). This call only exists in the capture path (empty crate). If protection
 * denies, we return null — the existing code handles null target naturally
 * by setting InteractionState.Failed.
 *
 * <p>Uses bridge slot 20 (evaluateUse) for protection checks.
 * Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES.
 * Fail-open on error.
 */
@Mixin(UseCaptureCrateInteraction.class)
public abstract class CaptureCrateGate {

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    // Cached hook: {impl, MethodHandle evaluate, MethodHandle fetchReason}
    @Unique
    private static volatile Object[] cachedHook;

    static {
        System.setProperty("hyperprotect.intercept.capture_crate_entity", "true");
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
            System.err.println("[HyperProtect] CaptureCrateGate error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object impl = getBridge(20); // slot 20 = Use hook
        if (impl == null) {
            cachedHook = null;
            return null;
        }

        Object[] cached = cachedHook;
        if (cached != null && cached[0] == impl) {
            return cached;
        }

        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), "evaluateUse", EVALUATE_TYPE);
            MethodHandle secondary = null;
            try {
                secondary = MethodHandles.publicLookup().findVirtual(
                        impl.getClass(), "fetchUseDenyReason", FETCH_REASON_TYPE);
            } catch (NoSuchMethodException ignored) {}

            cached = new Object[] { impl, primary, secondary };
            cachedHook = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    @Unique
    private static void sendDenyMessage(Object[] hook, PlayerRef playerRef,
                                         UUID playerUuid, String worldName,
                                         int x, int y, int z) {
        try {
            if (hook.length < 3 || hook[2] == null) return;
            String raw = (String) ((MethodHandle) hook[2]).invoke(hook[0],
                    playerUuid, worldName, x, y, z);
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
     * Redirects the {@code context.getTargetEntity()} call inside
     * UseCaptureCrateInteraction.tick0().
     *
     * <p>This call only exists in the entity capture path (empty crate +
     * targeting an animal). If protection denies, returns null — the existing
     * code handles this as InteractionState.Failed naturally.
     */
    @Redirect(
        method = "tick0",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/entity/InteractionContext;getTargetEntity()Lcom/hypixel/hytale/component/Ref;"),
        require = 0
    )
    private Ref<EntityStore> gateEntityCapture(InteractionContext context) {
        Ref<EntityStore> targetEntity = context.getTargetEntity();
        if (targetEntity == null) return null;

        try {
            Object[] hook = resolveHook();
            if (hook != null) {
                CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
                if (commandBuffer != null) {
                    Ref<EntityStore> ref = context.getEntity();
                    Player player = commandBuffer.getComponent(ref, Player.getComponentType());
                    if (player != null) {
                        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                        if (playerRef != null) {
                            World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
                            if (world != null) {
                                TransformComponent transform = commandBuffer.getComponent(
                                        ref, TransformComponent.getComponentType());
                                if (transform != null) {
                                    Vector3d pos = transform.getPosition();
                                    UUID playerUuid = playerRef.getUuid();
                                    String worldName = world.getName();

                                    System.getProperties().put("hyperprotect.context.interaction",
                                            "UseCaptureCrateInteraction(entity-capture)");

                                    int verdict = (int) ((MethodHandle) hook[1]).invoke(
                                            hook[0], playerUuid, worldName,
                                            (int) pos.x(), (int) pos.y(), (int) pos.z());

                                    if (verdict >= 1 && verdict <= 3) {
                                        if (verdict == 1) {
                                            sendDenyMessage(hook, playerRef, playerUuid, worldName,
                                                    (int) pos.x(), (int) pos.y(), (int) pos.z());
                                        }
                                        // Return null — existing code sets InteractionState.Failed
                                        return null;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            reportFault(t);
        }
        return targetEntity; // Allowed — pass through original target
    }
}
