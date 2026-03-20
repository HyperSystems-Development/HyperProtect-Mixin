package com.hyperprotect.mixin.intercept.items;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3d;
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
 * Intercepts durability changes on Player.updateItemStackDurability().
 * Redirects the super.updateItemStackDurability() call — returns null
 * (no transaction = no durability change) when the hook denies.
 *
 * <p>Hook contract (durability slot):
 * <pre>
 *   int evaluateWear(UUID playerUuid, String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW (durability decreases), non-zero=DENY (prevent durability loss)
 * </pre>
 *
 * <p>No messaging needed — durability is a passive mechanic.
 */
@Mixin(Player.class)
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
     * Redirect the super.updateItemStackDurability() call inside Player.updateItemStackDurability().
     * If the hook denies, return null (no transaction = durability unchanged).
     * Otherwise, delegate to the original LivingEntity.updateItemStackDurability().
     */
    @Redirect(
        method = "updateItemStackDurability",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/entity/LivingEntity;updateItemStackDurability(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Lcom/hypixel/hytale/server/core/inventory/container/ItemContainer;IDLcom/hypixel/hytale/component/ComponentAccessor;)Lcom/hypixel/hytale/server/core/inventory/transaction/ItemStackSlotTransaction;"
        ),
        require = 0
    )
    @Nullable
    private ItemStackSlotTransaction interceptUpdateDurability(
            LivingEntity instance, @Nonnull Ref<EntityStore> ref, @Nonnull ItemStack itemStack,
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
                                    (int) pos.getX(), (int) pos.getY(), (int) pos.getZ());

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

        // Allow: call the original LivingEntity implementation
        return instance.updateItemStackDurability(ref, itemStack, container, slotId, durabilityChange, componentAccessor);
    }
}
