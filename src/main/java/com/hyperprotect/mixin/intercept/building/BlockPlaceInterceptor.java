package com.hyperprotect.mixin.intercept.building;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.BlockRotation;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.BlockPlaceUtils;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.PlaceBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts block placement in PlaceBlockInteraction.tick0().
 *
 * <p>Uses two {@code @Redirect} points instead of {@code @Inject} to avoid
 * runtime dependency on {@code CallbackInfo} (not on TransformingClassLoader classpath).
 *
 * <p>Hook contract (block_place slot, index 18):
 * <ul>
 *   <li>Primary: {@code int evaluateBlockPlace(UUID, String, int, int, int)} — returns verdict</li>
 *   <li>Secondary: {@code String fetchBlockPlaceDenyReason(UUID, String, int, int, int)} — returns deny reason text</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES.
 * Fail-open on error.
 */
@Mixin(PlaceBlockInteraction.class)
public class BlockPlaceInterceptor {

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

    @Unique
    private static final ThreadLocal<InteractionContext> capturedContext = new ThreadLocal<>();

    static {
        System.setProperty("hyperprotect.intercept.block_place", "true");
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
            System.err.println("[HyperProtect] BlockPlaceInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Message formatReason(String reason) {
        if (reason == null || reason.isEmpty()) return null;
        Object handle = getBridge(15); // format_handle = 15
        if (handle instanceof MethodHandle mh) {
            try { return (Message) mh.invoke(reason); } catch (Throwable ignored) {}
        }
        return Message.raw(reason);
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(18); // block_place = 18
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateBlockPlace", EVALUATE_TYPE);
            MethodHandle secondary = null;
            try {
                secondary = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), "fetchBlockPlaceDenyReason", FETCH_REASON_TYPE);
            } catch (NoSuchMethodException ignored) {}
            cached = new Object[] { impl, primary, secondary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    // --- Redirect 1: Capture InteractionContext ---

    /**
     * Captures the InteractionContext for use in the placeBlock redirect.
     * Redirects context.getEntity() which is called once in tick0 before the placement.
     */
    @Redirect(
        method = "tick0",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/entity/InteractionContext;getEntity()Lcom/hypixel/hytale/component/Ref;")
    )
    private Ref<EntityStore> captureContext(InteractionContext ctx) {
        capturedContext.set(ctx);
        return ctx.getEntity();
    }

    // --- Redirect 2: Gate block placement ---

    /**
     * Intercepts BlockPlaceUtils.placeBlock() to evaluate protection permissions.
     * If denied, skips placement, sets interaction state to Failed, and sends denial message.
     */
    @Redirect(
        method = "tick0",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/modules/interaction/BlockPlaceUtils;placeBlock(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Ljava/lang/String;Lcom/hypixel/hytale/server/core/inventory/container/ItemContainer;Lcom/hypixel/hytale/math/vector/Vector3i;Lcom/hypixel/hytale/math/vector/Vector3i;Lcom/hypixel/hytale/protocol/BlockRotation;Lcom/hypixel/hytale/server/core/inventory/Inventory;BZLcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;Lcom/hypixel/hytale/component/ComponentAccessor;Z)V")
    )
    private void gatePlaceBlock(Ref<EntityStore> ref,
                                ItemStack itemStack,
                                @Nullable String blockTypeKey,
                                ItemContainer itemContainer,
                                Vector3i placementNormal,
                                Vector3i blockPosition,
                                BlockRotation blockRotation,
                                @Nullable Inventory inventory,
                                byte activeSlot,
                                boolean removeItemInHand,
                                Ref<ChunkStore> chunkReference,
                                ComponentAccessor<ChunkStore> chunkStore,
                                ComponentAccessor<EntityStore> entityStore,
                                boolean quickReplace) {
        try {
            Object[] hook = resolveHook();
            if (hook != null) {
                PlayerRef playerRef = entityStore.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef != null) {
                    UUID playerUuid = playerRef.getUuid();
                    World world = ((EntityStore) entityStore.getExternalData()).getWorld();
                    String worldName = world != null ? world.getName() : "";

                    int verdict = (int) ((MethodHandle) hook[1]).invoke(
                            hook[0], playerUuid, worldName,
                            blockPosition.getX(), blockPosition.getY(), blockPosition.getZ());

                    if (verdict == 1 || verdict == 2 || verdict == 3) {
                        // Denied — send message if DENY_WITH_MESSAGE
                        if (verdict == 1 && hook.length >= 3 && hook[2] != null) {
                            String reason = (String) ((MethodHandle) hook[2]).invoke(
                                    hook[0], playerUuid, worldName,
                                    blockPosition.getX(), blockPosition.getY(), blockPosition.getZ());
                            Player player = entityStore.getComponent(ref, Player.getComponentType());
                            if (player != null) {
                                Message msg = formatReason(reason);
                                if (msg != null) player.sendMessage(msg);
                            }
                        }

                        // Set interaction state to Failed via captured context
                        InteractionContext ctx = capturedContext.get();
                        if (ctx != null) {
                            ctx.getState().state = InteractionState.Failed;
                        }
                        return; // Skip placement
                    }
                }
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: proceed with placement
        }

        // Allowed — call original
        BlockPlaceUtils.placeBlock(ref, itemStack, blockTypeKey, itemContainer,
                placementNormal, blockPosition, blockRotation, inventory,
                activeSlot, removeItemInHand, chunkReference, chunkStore, entityStore, quickReplace);
    }
}
