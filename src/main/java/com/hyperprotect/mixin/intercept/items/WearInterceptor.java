package com.hyperprotect.mixin.intercept.items;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts durability changes in LivingEntity.updateItemStackDurability().
 * Redirects the inner {@code ItemUtils.updateItemStackDurability(...)} call —
 * returns null (no transaction = no durability change) when the hook denies.
 *
 * <p>In 0.5.3 {@code Player} no longer overrides {@code updateItemStackDurability}
 * and the durability logic moved out of the entity hierarchy into the static
 * {@code ItemUtils.updateItemStackDurability}. {@code LivingEntity.updateItemStackDurability}
 * now simply delegates to it, so this mixin targets {@code LivingEntity} and redirects
 * that static call. The hook only fires for players (non-player entities pass through),
 * and the allow path re-invokes the static impl — there is no recursion risk.
 *
 * <p>Hook contract (durability slot):
 * <pre>
 *   int evaluateWear(UUID playerUuid, String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW (durability decreases), non-zero=DENY (prevent durability loss)
 * </pre>
 *
 * <p>No messaging needed — durability is a passive mechanic.
 */
@Mixin(LivingEntity.class)
public abstract class WearInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    static {
        System.setProperty("hyperprotect.intercept.durability", "true");
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
            System.err.println("[HyperProtect] WearInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(6);
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateWear",
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
     * Redirect the {@code ItemUtils.updateItemStackDurability(...)} call inside
     * {@code LivingEntity.updateItemStackDurability()}.
     * If the hook denies, return null (no transaction = durability unchanged).
     * Otherwise, invoke the real static durability impl.
     *
     * <p>The redirected call is a plain static method on a different class
     * ({@code ItemUtils}), so re-invoking it on the allow path cannot re-enter this
     * redirect — no recursion risk.
     */
    @Redirect(
        method = "updateItemStackDurability",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/entity/ItemUtils;updateItemStackDurability(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Lcom/hypixel/hytale/server/core/inventory/container/ItemContainer;IDLcom/hypixel/hytale/component/ComponentAccessor;)Lcom/hypixel/hytale/server/core/inventory/transaction/ItemStackSlotTransaction;"
        ),
        require = 0
    )
    @Nullable
    private ItemStackSlotTransaction interceptUpdateDurability(
            @Nonnull Ref<EntityStore> ref, @Nonnull ItemStack itemStack,
            ItemContainer container, int slotId, double durabilityChange,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        try {
            Object[] hook = resolveHook();
            if (hook != null) {
                PlayerRef playerRef = componentAccessor.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef != null) {
                    World world = ((EntityStore) componentAccessor.getExternalData()).getWorld();
                    String worldName = world != null ? world.getName() : null;
                    if (worldName != null) {
                        TransformComponent transform = componentAccessor.getComponent(ref, TransformComponent.getComponentType());
                        if (transform != null) {
                            Vector3d pos = transform.getPosition();
                            UUID playerUuid = playerRef.getUuid();

                            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                                    hook[0], playerUuid, worldName,
                                    (int) pos.x(), (int) pos.y(), (int) pos.z());

                            if (verdict != 0) {
                                return null; // Deny: no transaction = no durability change
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: allow normal durability behavior
        }

        // Allow: invoke the real static durability impl. Because ItemUtils is a
        // different class, this does not re-enter the redirect (no recursion).
        return ItemUtils.updateItemStackDurability(ref, itemStack, container, slotId, durabilityChange, componentAccessor);
    }
}
