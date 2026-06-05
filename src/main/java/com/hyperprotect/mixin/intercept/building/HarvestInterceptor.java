package com.hyperprotect.mixin.intercept.building;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.BlockInteractionUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 * Intercepts block harvesting (break + interactive pickup) in BlockHarvestUtils.
 *
 * Uses a consolidated ThreadLocal {@link HarvestContext} record to track the current
 * player, target block, and verdict across the five injection points within
 * {@code performPickupByInteraction()}.
 *
 * <p>Hook contract (block_break slot):
 * <ul>
 *   <li>Primary: {@code int evaluate(UUID, String, int, int, int)} — returns verdict</li>
 *   <li>Secondary: {@code String fetchDenyReason(UUID, String, int, int, int)} — returns deny reason text</li>
 * </ul>
 *
 * <p>Hook contract (item_pickup slot, for interactive pickup):
 * <ul>
 *   <li>Primary: {@code int evaluatePickup(UUID, String, int, int, int)} — returns verdict</li>
 *   <li>Secondary: {@code String fetchPickupDenyReason(UUID, String, int, int, int)} — returns deny reason text</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES, negative/unknown=ALLOW
 */
@Mixin(BlockHarvestUtils.class)
public abstract class HarvestInterceptor {

    // --- Verdict constants ---

    @Unique private static final int ALLOW            = 0;
    @Unique private static final int DENY_WITH_MESSAGE = 1;
    @Unique private static final int DENY_SILENT       = 2;
    @Unique private static final int DENY_MOD_HANDLES  = 3;

    // --- Consolidated ThreadLocal context ---

    @Unique
    private record HarvestContext(int verdict, String reason, Player actor, Vector3i target) {
        static final HarvestContext EMPTY = new HarvestContext(ALLOW, null, null, null);

        HarvestContext withVerdict(int verdict) {
            return new HarvestContext(verdict, this.reason, this.actor, this.target);
        }
        HarvestContext withReason(String reason) {
            return new HarvestContext(this.verdict, reason, this.actor, this.target);
        }
        HarvestContext withActor(Player actor) {
            return new HarvestContext(this.verdict, this.reason, actor, this.target);
        }
        HarvestContext withTarget(Vector3i target) {
            return new HarvestContext(this.verdict, this.reason, this.actor, target);
        }
    }

    @Unique
    private static final ThreadLocal<HarvestContext> context = new ThreadLocal<>();

    // --- Fault tracking ---

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    // --- Cached hooks (volatile for cross-thread visibility) ---

    @Unique private static volatile Object[] breakHookCache;
    @Unique private static volatile Object[] pickupHookCache;

    // --- MethodType constants for hook resolution ---

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.block_break", "true");
    }

    @Shadow
    protected static void removeBlock(Vector3i blockPosition, BlockType blockType,
                                      int setBlockSettings, Ref<ChunkStore> chunkReference,
                                      ComponentAccessor<ChunkStore> chunkStore) {
        throw new UnsupportedOperationException("Shadow stub");
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
            System.err.println("[HyperProtect] HarvestInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    // --- Hook resolution helpers ---

    @Unique
    private static Object[] resolveBreakHook() {
        Object[] cached = breakHookCache;
        Object impl = getBridge(0); // block_break = 0
        if (impl == null) {
            breakHookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluate", EVALUATE_TYPE);
            MethodHandle secondary = null;
            try {
                secondary = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), "fetchDenyReason", FETCH_REASON_TYPE);
            } catch (NoSuchMethodException ignored) {}
            cached = new Object[] { impl, primary, secondary };
            breakHookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    @Unique
    private static Object[] resolvePickupHook() {
        Object[] cached = pickupHookCache;
        Object impl = getBridge(4); // item_pickup = 4
        if (impl == null) {
            pickupHookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluatePickup", EVALUATE_TYPE);
            MethodHandle secondary = null;
            try {
                secondary = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), "fetchPickupDenyReason", FETCH_REASON_TYPE);
            } catch (NoSuchMethodException ignored) {}
            cached = new Object[] { impl, primary, secondary };
            pickupHookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    // --- Message formatting via bridge handle ---

    @Unique
    private static Message formatReason(String reason) {
        if (reason == null || reason.isEmpty()) return null;
        Object handle = getBridge(15); // format_handle = 15
        if (handle instanceof MethodHandle mh) {
            try { return (Message) mh.invoke(reason); } catch (Throwable ignored) {}
        }
        return Message.raw(reason);
    }

    // --- Injection points ---

    /**
     * Stage 1: Reset context at the start of performPickupByInteraction.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/asset/type/blocktype/config/BlockType;isUnknown()Z")
    )
    private static boolean resetContext(BlockType blockType) {
        context.set(HarvestContext.EMPTY);
        return blockType.isUnknown();
    }

    /**
     * Stage 2: Capture the target block coordinates.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/universe/world/chunk/section/BlockSection;getRotationIndex(III)I")
    )
    private static int snapshotTarget(BlockSection section, int x, int y, int z) {
        HarvestContext ctx = context.get();
        if (ctx != null) {
            context.set(ctx.withTarget(new Vector3i(x, y, z)));
        }
        return section.getRotationIndex(x, y, z);
    }

    /**
     * Stage 3: Evaluate block break permission before the action proceeds.
     * No bypass checks -- the hook implementation decides.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/modules/interaction/BlockInteractionUtils;isNaturalAction(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;)Z")
    )
    private static boolean gateAction(Ref<EntityStore> ref, ComponentAccessor<EntityStore> entityStore) {
        try {
            Player player = (Player) entityStore.getComponent(ref, Player.getComponentType());
            HarvestContext ctx = context.get();
            if (ctx != null) {
                context.set(ctx.withActor(player));
            }

            PlayerRef playerRef = (PlayerRef) entityStore.getComponent(ref, PlayerRef.getComponentType());
            UUID playerUuid = playerRef != null ? playerRef.getUuid() : null;
            World world = ((EntityStore) entityStore.getExternalData()).getWorld();
            String worldName = world != null ? world.getName() : "unknown";
            Vector3i targetBlock = ctx != null ? ctx.target() : null;

            if (targetBlock != null) {
                Object[] hook = resolveBreakHook();
                if (hook != null) {
                    int verdict = (int) ((MethodHandle) hook[1]).invoke(
                            hook[0], playerUuid, worldName,
                            targetBlock.x(), targetBlock.y(), targetBlock.z());

                    if (verdict == DENY_WITH_MESSAGE || verdict == DENY_SILENT || verdict == DENY_MOD_HANDLES) {
                        String reason = null;
                        if (verdict == DENY_WITH_MESSAGE && hook.length >= 3 && hook[2] != null) {
                            reason = (String) ((MethodHandle) hook[2]).invoke(
                                    hook[0], playerUuid, worldName,
                                    targetBlock.x(), targetBlock.y(), targetBlock.z());
                        }
                        ctx = context.get();
                        if (ctx != null) {
                            context.set(ctx.withVerdict(verdict).withReason(reason));
                        }
                    }
                }
            }
        } catch (Throwable e) {
            reportFault(e);
        }
        return BlockInteractionUtils.isNaturalAction(ref, entityStore);
    }

    /**
     * Stage 4: If denied, skip block removal, resync client, and send denial message.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/modules/interaction/BlockHarvestUtils;removeBlock(Lorg/joml/Vector3i;Lcom/hypixel/hytale/server/core/asset/type/blocktype/config/BlockType;ILcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;)V")
    )
    private static void interceptRemoval(Vector3i blockPosition, BlockType blockType,
                                         int setBlockSettings, Ref<ChunkStore> chunkReference,
                                         ComponentAccessor<ChunkStore> chunkStore) {
        HarvestContext ctx = context.get();
        int verdict = ctx != null ? ctx.verdict() : ALLOW;

        if (verdict == DENY_WITH_MESSAGE || verdict == DENY_SILENT || verdict == DENY_MOD_HANDLES) {
            // Invalidate block to resync client
            try {
                BlockChunk blockChunk = (BlockChunk) chunkStore.getComponent(chunkReference, BlockChunk.getComponentType());
                if (blockChunk != null) {
                    BlockSection section = blockChunk.getSectionAtBlockY(blockPosition.y());
                    if (section != null) {
                        section.invalidateBlock(blockPosition.x(), blockPosition.y(), blockPosition.z());
                    }
                }
            } catch (Exception ignored) {
            }

            // Send denial message for DENY_WITH_MESSAGE
            if (verdict == DENY_WITH_MESSAGE && ctx != null) {
                try {
                    Player player = ctx.actor();
                    Message msg = formatReason(ctx.reason());
                    if (player != null && msg != null) {
                        player.getPlayerRef().sendMessage(msg);
                    }
                } catch (Exception ignored) {
                }
            }
            return;
        }

        removeBlock(blockPosition, blockType, setBlockSettings, chunkReference, chunkStore);
    }

    /**
     * Stage 5: If denied, skip item pickup. Also evaluates pickup permission independently.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/entity/ItemUtils;interactivelyPickupItem(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Lorg/joml/Vector3d;Lcom/hypixel/hytale/component/ComponentAccessor;)V")
    )
    private static void interceptCollection(Ref<EntityStore> ref, ItemStack itemStack,
                                            Vector3d origin, ComponentAccessor<EntityStore> componentAccessor) {
        HarvestContext ctx = context.get();
        int breakVerdict = ctx != null ? ctx.verdict() : ALLOW;

        // If block break was denied, skip pickup entirely
        if (breakVerdict == DENY_WITH_MESSAGE || breakVerdict == DENY_SILENT || breakVerdict == DENY_MOD_HANDLES) {
            return;
        }

        // Evaluate pickup permission independently
        try {
            PlayerRef playerRef = (PlayerRef) componentAccessor.getComponent(ref, PlayerRef.getComponentType());
            UUID playerUuid = playerRef != null ? playerRef.getUuid() : null;
            World world = ((EntityStore) componentAccessor.getExternalData()).getWorld();
            String worldName = world != null ? world.getName() : null;

            if (worldName != null && origin != null) {
                Object[] hook = resolvePickupHook();
                if (hook != null) {
                    int pickupVerdict = (int) ((MethodHandle) hook[1]).invoke(
                            hook[0], playerUuid, worldName,
                            (int) origin.x(), (int) origin.y(), (int) origin.z());

                    if (pickupVerdict == DENY_WITH_MESSAGE || pickupVerdict == DENY_SILENT || pickupVerdict == DENY_MOD_HANDLES) {
                        // Send message for DENY_WITH_MESSAGE
                        if (pickupVerdict == DENY_WITH_MESSAGE && hook.length >= 3 && hook[2] != null) {
                            String reason = (String) ((MethodHandle) hook[2]).invoke(
                                    hook[0], playerUuid, worldName,
                                    (int) origin.x(), (int) origin.y(), (int) origin.z());
                            Player player = (Player) componentAccessor.getComponent(ref, Player.getComponentType());
                            Message msg = formatReason(reason);
                            if (player != null && msg != null) {
                                player.getPlayerRef().sendMessage(msg);
                            }
                        }
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            reportFault(e);
        }

        ItemUtils.interactivelyPickupItem(ref, itemStack, origin, componentAccessor);
    }
}
