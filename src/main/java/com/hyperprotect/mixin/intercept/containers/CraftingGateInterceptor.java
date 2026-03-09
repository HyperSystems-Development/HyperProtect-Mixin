package com.hyperprotect.mixin.intercept.containers;

import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
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
 * Intercepts crafting in CraftingManager.craftItem() to check container access
 * and crafting resource permissions.
 *
 * <p>Uses bench position from the CraftingManager shadow fields (x, y, z).
 *
 * <p>Uses {@code @Redirect} instead of {@code @Inject} to avoid runtime dependency
 * on {@code CallbackInfo} (not on TransformingClassLoader classpath).
 *
 * <p>Checks two hooks:
 * <ul>
 *   <li><b>Slot 7 (container_access)</b>: {@code int evaluateCrafting(UUID, String, int, int, int)} — verdict</li>
 *   <li><b>Slot 23 (crafting_resource)</b>: {@code boolean evaluateChestAccess(UUID, String, int, int, int, int, int, int)} — allow/deny</li>
 * </ul>
 *
 * <p>Verdict protocol (slot 7): 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES.
 * Fail-open on error.
 */
@Mixin(CraftingManager.class)
public abstract class CraftingGateInterceptor {

    @Shadow
    private int x;

    @Shadow
    private int y;

    @Shadow
    private int z;

    @Shadow
    private boolean isValidBenchForRecipe(Ref<EntityStore> ref,
                                           ComponentAccessor<EntityStore> componentAccessor,
                                           CraftingRecipe recipe) {
        throw new AssertionError();
    }

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static volatile Object[] resourceHookCache;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType RESOURCE_EVALUATE_TYPE = MethodType.methodType(
            boolean.class, UUID.class, String.class,
            int.class, int.class, int.class,
            int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.container_access", "true");
        System.setProperty("hyperprotect.intercept.crafting_resource", "true");
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
            System.err.println("[HyperProtect] CraftingGateInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(7);
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateCrafting", EVALUATE_TYPE);
            MethodHandle secondary = null;
            try {
                secondary = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), "fetchCraftingDenyReason", FETCH_REASON_TYPE);
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
    private static Object[] resolveResourceHook() {
        Object[] cached = resourceHookCache;
        Object impl = getBridge(23); // crafting_resource
        if (impl == null) {
            resourceHookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateChestAccess", RESOURCE_EVALUATE_TYPE);
            cached = new Object[] { impl, primary };
            resourceHookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    @Unique
    private static void formatReason(Object[] hook, Player player,
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
     * Redirects the isValidBenchForRecipe() call inside craftItem().
     * If the bench is invalid, returns false (original behavior).
     * If the bench is valid, checks both protection hooks before allowing crafting:
     * <ol>
     *   <li>Slot 7 (container_access) — can the player craft at this bench?</li>
     *   <li>Slot 23 (crafting_resource) — can the player access nearby chests for resources?</li>
     * </ol>
     */
    @Redirect(
        method = "craftItem",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/builtin/crafting/component/CraftingManager;isValidBenchForRecipe(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;Lcom/hypixel/hytale/server/core/asset/type/item/config/CraftingRecipe;)Z")
    )
    private boolean gateCraftingAccess(CraftingManager self,
                                       Ref<EntityStore> ref,
                                       ComponentAccessor<EntityStore> componentAccessor,
                                       CraftingRecipe recipe) {
        try {
            // Call original bench validation first
            boolean valid = this.isValidBenchForRecipe(ref, componentAccessor, recipe);
            if (!valid) return false;

            // Check protection hooks
            PlayerRef playerRef = componentAccessor.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return true;

            String worldName = null;
            try {
                worldName = ((EntityStore) componentAccessor.getExternalData())
                        .getWorld().getName();
            } catch (Exception ignored) {}
            if (worldName == null) return true;

            UUID playerUuid = playerRef.getUuid();

            // Hook slot 7: container_access
            Object[] hook = resolveHook();
            if (hook != null) {
                int verdict = (int) ((MethodHandle) hook[1]).invoke(
                        hook[0], playerUuid, worldName,
                        this.x, this.y, this.z);

                if (verdict >= 1 && verdict <= 3) {
                    if (verdict == 1) {
                        Player player = componentAccessor.getComponent(ref, Player.getComponentType());
                        if (player != null) {
                            formatReason(hook, player, playerUuid, worldName,
                                    this.x, this.y, this.z);
                        }
                    }
                    return false; // Deny crafting
                }
            }

            // Hook slot 23: crafting_resource
            Object[] resourceHook = resolveResourceHook();
            if (resourceHook != null) {
                boolean allowed = (boolean) ((MethodHandle) resourceHook[1]).invoke(
                        resourceHook[0], playerUuid, worldName,
                        this.x, this.y, this.z,
                        this.x, this.y, this.z);
                if (!allowed) return false;
            }

            return true;
        } catch (Throwable t) {
            reportFault(t);
            // Fail-safe: call original method if possible, otherwise allow
            try {
                return this.isValidBenchForRecipe(ref, componentAccessor, recipe);
            } catch (Throwable ignored) {
                return true; // Fail-open: allow crafting
            }
        }
    }
}
