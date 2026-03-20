package com.hyperprotect.mixin.intercept.interaction;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
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
 * Consolidated gate for SimpleBlockInteraction subclasses that don't override tick0.
 *
 * <p>SimpleBlockInteraction.tick0() validates the interaction then calls
 * {@code this.interactWithBlock(...)}. We redirect that call site to check
 * protection hooks before allowing the interaction to proceed.
 *
 * <p>At runtime, checks {@code this.getClass()} to determine which hook slot
 * and method name to use. Unknown subclasses pass through (fail-open).
 *
 * <p>Replaces individual interceptors that targeted tick0 on subclasses where
 * the bytecode doesn't exist: HammerInterceptor, UseInterceptor,
 * TeleporterInterceptor, PortalGateEntry, PortalGateReturn, InstanceConfigGate,
 * SeatInterceptor, ContainerOpenInterceptor.
 *
 * <p>Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES.
 * Fail-open on error.
 */
@Mixin(SimpleBlockInteraction.class)
public abstract class SimpleBlockInteractionGate {

    // Hook definition per class: {Integer bridgeSlot, String evaluateName, String fetchReasonName}
    @Unique
    private static final Map<String, Object[]> HOOK_DEFS;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    // Per-player deny message deduplication: UUID -> last deny timestamp (nanos)
    // Suppresses duplicate messages when multiple interaction types fire for the same F-key press
    @Unique
    private static final ConcurrentHashMap<UUID, Long> lastDenyTime = new ConcurrentHashMap<>();

    @Unique
    private static final long DENY_DEDUP_NANOS = 500_000_000L; // 500ms

    // Per-class resolved hook cache: className -> {impl, MethodHandle evaluate, MethodHandle fetchReason}
    @Unique
    private static final ConcurrentHashMap<String, Object[]> resolvedHooks = new ConcurrentHashMap<>();

    static {
        Map<String, Object[]> map = new HashMap<>();

        // === Core block interactions (F-key use, block breaking, block changes) ===
        map.put("com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.UseBlockInteraction",
                new Object[] { 20, "evaluateUse", "fetchUseDenyReason" });
        map.put("com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.BreakBlockInteraction",
                new Object[] { 0, "evaluate", "fetchDenyReason" });
        map.put("com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.ChangeBlockInteraction",
                new Object[] { 18, "evaluateBlockPlace", "fetchBlockPlaceDenyReason" });
        map.put("com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.ChangeStateInteraction",
                new Object[] { 20, "evaluateUse", "fetchUseDenyReason" });

        // === Hammer (cycle block group) ===
        map.put("com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.CycleBlockGroupInteraction",
                new Object[] { 19, "evaluateHammer", "fetchHammerDenyReason" });

        // === Farming interactions ===
        map.put("com.hypixel.hytale.builtin.adventure.farming.interactions.HarvestCropInteraction",
                new Object[] { 20, "evaluateUse", "fetchUseDenyReason" });
        map.put("com.hypixel.hytale.builtin.adventure.farming.interactions.ChangeFarmingStageInteraction",
                new Object[] { 20, "evaluateUse", "fetchUseDenyReason" });
        map.put("com.hypixel.hytale.builtin.adventure.farming.interactions.FertilizeSoilInteraction",
                new Object[] { 20, "evaluateUse", "fetchUseDenyReason" });
        map.put("com.hypixel.hytale.builtin.adventure.farming.interactions.UseWateringCanInteraction",
                new Object[] { 20, "evaluateUse", "fetchUseDenyReason" });
        map.put("com.hypixel.hytale.builtin.adventure.farming.interactions.UseCaptureCrateInteraction",
                new Object[] { 20, "evaluateUse", "fetchUseDenyReason" });
        map.put("com.hypixel.hytale.builtin.adventure.farming.interactions.UseCoopInteraction",
                new Object[] { 20, "evaluateUse", "fetchUseDenyReason" });

        // === Transport (teleporters, portals, instances) ===
        map.put("com.hypixel.hytale.builtin.adventure.teleporter.interaction.server.TeleporterInteraction",
                new Object[] { 9, "evaluateTeleporter", "fetchTeleporterDenyReason" });
        map.put("com.hypixel.hytale.builtin.portals.interactions.EnterPortalInteraction",
                new Object[] { 10, "evaluateGateway", "fetchGatewayDenyReason" });
        map.put("com.hypixel.hytale.builtin.portals.interactions.ReturnPortalInteraction",
                new Object[] { 10, "evaluateGateway", "fetchGatewayDenyReason" });
        map.put("com.hypixel.hytale.builtin.instances.interactions.TeleportConfigInstanceInteraction",
                new Object[] { 10, "evaluateGateway", "fetchGatewayDenyReason" });

        // === Mounts / seating ===
        map.put("com.hypixel.hytale.builtin.mounts.interactions.SeatingInteraction",
                new Object[] { 21, "evaluateSeat", "fetchSeatDenyReason" });
        map.put("com.hypixel.hytale.builtin.mounts.interactions.SpawnMinecartInteraction",
                new Object[] { 18, "evaluateBlockPlace", "fetchBlockPlaceDenyReason" });

        // === Containers / crafting ===
        map.put("com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenContainerInteraction",
                new Object[] { 17, "evaluateContainerOpen", "fetchContainerOpenDenyReason" });
        map.put("com.hypixel.hytale.builtin.crafting.interaction.OpenProcessingBenchInteraction",
                new Object[] { 17, "evaluateContainerOpen", "fetchContainerOpenDenyReason" });
        map.put("com.hypixel.hytale.builtin.crafting.interaction.OpenBenchPageInteraction",
                new Object[] { 17, "evaluateContainerOpen", "fetchContainerOpenDenyReason" });

        HOOK_DEFS = map;

        System.setProperty("hyperprotect.intercept.use_block", "true");
        System.setProperty("hyperprotect.intercept.break_block_interaction", "true");
        System.setProperty("hyperprotect.intercept.change_block", "true");
        System.setProperty("hyperprotect.intercept.crop_harvest", "true");
        System.setProperty("hyperprotect.intercept.farming_stage", "true");
        System.setProperty("hyperprotect.intercept.fertilize", "true");
        System.setProperty("hyperprotect.intercept.watering_can", "true");
        System.setProperty("hyperprotect.intercept.capture_crate", "true");
        System.setProperty("hyperprotect.intercept.coop", "true");
        System.setProperty("hyperprotect.intercept.hammer", "true");
        System.setProperty("hyperprotect.intercept.use", "true");
        System.setProperty("hyperprotect.intercept.teleporter", "true");
        System.setProperty("hyperprotect.intercept.portal_entry", "true");
        System.setProperty("hyperprotect.intercept.portal_return", "true");
        System.setProperty("hyperprotect.intercept.instance_config", "true");
        System.setProperty("hyperprotect.intercept.seat", "true");
        System.setProperty("hyperprotect.intercept.minecart_spawn", "true");
        System.setProperty("hyperprotect.intercept.container_open", "true");
        System.setProperty("hyperprotect.intercept.processing_bench", "true");
        System.setProperty("hyperprotect.intercept.bench_page", "true");
    }

    @Shadow
    protected abstract void interactWithBlock(World world, CommandBuffer<EntityStore> commandBuffer,
        InteractionType type, InteractionContext context, ItemStack itemInHand,
        Vector3i targetBlock, CooldownHandler cooldownHandler);

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
            System.err.println("[HyperProtect] SimpleBlockInteractionGate error #" + count + ": " + t);
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
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), (String) hookDef[1], EVALUATE_TYPE);
            MethodHandle secondary = null;
            try {
                secondary = MethodHandles.publicLookup().findVirtual(
                        impl.getClass(), (String) hookDef[2], FETCH_REASON_TYPE);
            } catch (NoSuchMethodException ignored) {}

            cached = new Object[] { impl, primary, secondary };
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

            // Deduplicate: suppress if same player was denied within 500ms
            // (F-key creates multiple interaction types that all fire through tick0)
            long now = System.nanoTime();
            Long prev = lastDenyTime.put(playerUuid, now);
            if (prev != null && (now - prev) < DENY_DEDUP_NANOS) {
                return; // Suppress duplicate message
            }

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
     * Redirects the {@code this.interactWithBlock(...)} call inside
     * {@code SimpleBlockInteraction.tick0()}.
     *
     * <p>Checks protection hooks based on the runtime class of the interaction.
     * Unknown subclasses pass through without interception.
     */
    @Redirect(
        method = "tick0",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/modules/interaction/interaction/config/client/SimpleBlockInteraction;interactWithBlock(Lcom/hypixel/hytale/server/core/universe/world/World;Lcom/hypixel/hytale/component/CommandBuffer;Lcom/hypixel/hytale/protocol/InteractionType;Lcom/hypixel/hytale/server/core/entity/InteractionContext;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Lcom/hypixel/hytale/math/vector/Vector3i;Lcom/hypixel/hytale/server/core/modules/interaction/interaction/CooldownHandler;)V")
    )
    private void gateBlockInteraction(SimpleBlockInteraction self,
                                       World world, CommandBuffer<EntityStore> commandBuffer,
                                       InteractionType type, InteractionContext context,
                                       ItemStack itemInHand, Vector3i targetBlock,
                                       CooldownHandler cooldownHandler) {
        try {
            String className = self.getClass().getName();
            Object[] hook = resolveHook(className);
            if (hook != null) {
                Ref<EntityStore> ref = context.getEntity();
                Player player = commandBuffer.getComponent(ref, Player.getComponentType());
                if (player != null) {
                    PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                    if (playerRef != null) {
                        UUID playerUuid = playerRef.getUuid();
                        String worldName = world.getName();
                        int x = targetBlock.getX();
                        int y = targetBlock.getY();
                        int z = targetBlock.getZ();

                        // Pass interaction class name to hook for debug logging
                        System.getProperties().put("hyperprotect.context.interaction", className);

                        // Extract block type ID from world for debug/classification
                        try {
                            BlockType blockType = world.getBlockType(targetBlock);
                            if (blockType != null) {
                                String blockId = blockType.getId();
                                if (blockId != null) {
                                    System.getProperties().put("hyperprotect.context.block_id", blockId);
                                }
                                var state = blockType.getState();
                                if (state != null) {
                                    String stateStr = state.getStateForBlock(blockType.getId());
                                    if (stateStr != null) {
                                        System.getProperties().put("hyperprotect.context.block_state", stateStr);
                                    }
                                }
                            }
                        } catch (Exception ignored) {}

                        int verdict = (int) ((MethodHandle) hook[1]).invoke(
                                hook[0], playerUuid, worldName, x, y, z);

                        if (verdict >= 1 && verdict <= 3) {
                            if (verdict == 1) {
                                sendDenyMessage(hook, player, playerUuid, worldName, x, y, z);
                            }
                            context.getState().state = InteractionState.Failed;
                            return; // DENIED
                        }
                    }
                }
            }
        } catch (Throwable t) {
            reportFault(t);
        }
        // Allowed or unknown class — call original (this == self at runtime)
        this.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
    }
}
