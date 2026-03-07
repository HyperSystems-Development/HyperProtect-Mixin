package com.hyperprotect.mixin.intercept.interaction;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Consolidated gate for SimpleInstantInteraction subclasses that don't override tick0.
 *
 * <p>SimpleInstantInteraction.tick0() (final) calls {@code this.firstRun(...)}.
 * We redirect that call site to check protection hooks.
 *
 * <p>At runtime, checks {@code this.getClass()} to determine which hook slot
 * and method name to use. Unknown subclasses pass through (fail-open).
 *
 * <p>Replaces individual interceptors: InstanceJumpGate, InstanceLeaveGate, HubGate.
 *
 * <p>Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES.
 * Fail-open on error.
 */
@Mixin(SimpleInstantInteraction.class)
public abstract class SimpleInstantInteractionGate {

    // Hook definition per class: {Integer slot, String evaluateName, String fetchReasonName, Boolean useDoubleCoords}
    @Unique
    private static final Map<String, Object[]> HOOK_DEFS;

    @Unique
    private static final MethodType EVALUATE_INT_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType EVALUATE_DOUBLE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, double.class, double.class, double.class);

    @Unique
    private static final MethodType FETCH_REASON_INT_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    // Per-class resolved hook cache: className -> {impl, MethodHandle evaluate, MethodHandle fetchReason, Boolean useDoubleCoords}
    @Unique
    private static final ConcurrentHashMap<String, Object[]> resolvedHooks = new ConcurrentHashMap<>();

    static {
        Map<String, Object[]> map = new HashMap<>();
        map.put("com.hypixel.hytale.builtin.instances.interactions.TeleportInstanceInteraction",
                new Object[] { 10, "evaluateGateway", "fetchGatewayDenyReason", false });
        map.put("com.hypixel.hytale.builtin.instances.interactions.ExitInstanceInteraction",
                new Object[] { 10, "evaluateGateway", "fetchGatewayDenyReason", false });
        map.put("com.hypixel.hytale.builtin.creativehub.interactions.HubPortalInteraction",
                new Object[] { 10, "evaluateGateway", "fetchGatewayDenyReason", false });
        map.put("com.hypixel.hytale.builtin.buildertools.interactions.PickupItemInteraction",
                new Object[] { 4, "evaluate", null, true });

        // === NPC interactions (F-key tame, contextual NPC use) ===
        map.put("com.hypixel.hytale.server.npc.interactions.UseNPCInteraction",
                new Object[] { 20, "evaluateUse", "fetchUseDenyReason", false });
        map.put("com.hypixel.hytale.server.npc.interactions.ContextualUseNPCInteraction",
                new Object[] { 20, "evaluateUse", "fetchUseDenyReason", false });

        HOOK_DEFS = map;

        System.setProperty("hyperprotect.intercept.instance_teleport", "true");
        System.setProperty("hyperprotect.intercept.instance_exit", "true");
        System.setProperty("hyperprotect.intercept.hub_portal", "true");
        System.setProperty("hyperprotect.intercept.item_pickup_manual", "true");
        System.setProperty("hyperprotect.intercept.npc_use", "true");
        System.setProperty("hyperprotect.intercept.npc_contextual_use", "true");
    }

    @Shadow
    protected abstract void firstRun(InteractionType type, InteractionContext context,
                                      CooldownHandler cooldownHandler);

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
            System.err.println("[HyperProtect] SimpleInstantInteractionGate error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook(String className) {
        Object[] hookDef = HOOK_DEFS.get(className);
        if (hookDef == null) return null;

        int slot = (int) hookDef[0];
        Object impl = getBridge(slot);
        if (impl == null) {
            resolvedHooks.remove(className);
            return null;
        }

        Object[] cached = resolvedHooks.get(className);
        if (cached != null && cached[0] == impl) {
            return cached;
        }

        try {
            boolean useDoubles = (boolean) hookDef[3];
            MethodType evalType = useDoubles ? EVALUATE_DOUBLE_TYPE : EVALUATE_INT_TYPE;
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), (String) hookDef[1], evalType);
            MethodHandle secondary = null;
            if (hookDef[2] != null) {
                try {
                    secondary = MethodHandles.publicLookup().findVirtual(
                            impl.getClass(), (String) hookDef[2], FETCH_REASON_INT_TYPE);
                } catch (NoSuchMethodException ignored) {}
            }

            cached = new Object[] { impl, primary, secondary, useDoubles };
            resolvedHooks.put(className, cached);
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    @Unique
    private static void sendDenyMessage(Object[] hook, Player player,
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
                player.sendMessage(msg);
            }
        } catch (Throwable t) {
            reportFault(t);
        }
    }

    /**
     * Extracts the NPC role name from the target entity (if it's an NPC) and stores it
     * in system properties for the hook to read. Uses reflection to avoid NPC module imports.
     */
    @Unique
    private static void extractNpcRole(InteractionContext context,
                                        CommandBuffer<EntityStore> commandBuffer) {
        System.getProperties().remove("hyperprotect.context.npc_role");
        try {
            Ref<EntityStore> targetRef = context.getTargetEntity();
            if (targetRef == null || !targetRef.isValid()) return;

            Class<?> npcClass = Class.forName("com.hypixel.hytale.server.npc.entities.NPCEntity");
            var getCompType = npcClass.getMethod("getComponentType");
            @SuppressWarnings("unchecked")
            var compType = (com.hypixel.hytale.component.ComponentType<EntityStore, ?>) getCompType.invoke(null);
            Object npcEntity = commandBuffer.getComponent(targetRef, compType);
            if (npcEntity == null) return;

            var getRoleName = npcClass.getMethod("getRoleName");
            String roleName = (String) getRoleName.invoke(npcEntity);
            if (roleName != null) {
                System.getProperties().put("hyperprotect.context.npc_role", roleName);
            }
        } catch (ClassNotFoundException ignored) {
            // NPC module not loaded
        } catch (Exception ignored) {}
    }

    /**
     * Redirects the {@code this.firstRun(...)} call inside
     * {@code SimpleInstantInteraction.tick0()}.
     *
     * <p>Checks protection hooks based on the runtime class of the interaction.
     * Uses player position (firstRun has no target block parameter).
     * Unknown subclasses pass through without interception.
     */
    @Redirect(
        method = "tick0",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/modules/interaction/interaction/config/SimpleInstantInteraction;firstRun(Lcom/hypixel/hytale/protocol/InteractionType;Lcom/hypixel/hytale/server/core/entity/InteractionContext;Lcom/hypixel/hytale/server/core/modules/interaction/interaction/CooldownHandler;)V")
    )
    private void gateInstantInteraction(SimpleInstantInteraction self,
                                         InteractionType type, InteractionContext context,
                                         CooldownHandler cooldownHandler) {
        try {
            Object[] hook = resolveHook(self.getClass().getName());
            if (hook != null) {
                CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
                if (commandBuffer != null) {
                    Ref<EntityStore> ref = context.getEntity();
                    Player player = commandBuffer.getComponent(ref, Player.getComponentType());
                    PlayerRef playerRef = player != null
                            ? commandBuffer.getComponent(ref, PlayerRef.getComponentType()) : null;
                    if (playerRef != null) {
                        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
                        if (world != null) {
                            TransformComponent transform = commandBuffer.getComponent(
                                    ref, TransformComponent.getComponentType());
                            if (transform != null) {
                                Vector3d pos = transform.getPosition();
                                UUID playerUuid = playerRef.getUuid();
                                String worldName = world.getName();

                                // Pass interaction class name to hook for debug logging
                                System.getProperties().put("hyperprotect.context.interaction",
                                        self.getClass().getName());

                                // For NPC interactions, extract the NPC role name via reflection
                                extractNpcRole(context, commandBuffer);

                                boolean useDoubles = (boolean) hook[3];
                                int verdict;
                                if (useDoubles) {
                                    verdict = (int) ((MethodHandle) hook[1]).invoke(
                                            hook[0], playerUuid, worldName,
                                            pos.getX(), pos.getY(), pos.getZ());
                                } else {
                                    verdict = (int) ((MethodHandle) hook[1]).invoke(
                                            hook[0], playerUuid, worldName,
                                            (int) pos.getX(), (int) pos.getY(), (int) pos.getZ());
                                }

                                if (verdict >= 1 && verdict <= 3) {
                                    if (verdict == 1) {
                                        sendDenyMessage(hook, player, playerUuid, worldName,
                                                (int) pos.getX(), (int) pos.getY(), (int) pos.getZ());
                                    }
                                    context.getState().state = InteractionState.Failed;
                                    return; // DENIED
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            reportFault(t);
        }
        // Allowed or unknown class — call original (this == self at runtime)
        this.firstRun(type, context, cooldownHandler);
    }
}
