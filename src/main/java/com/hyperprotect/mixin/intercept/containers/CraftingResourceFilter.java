package com.hyperprotect.mixin.intercept.containers;

import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Filters nearby chests during bench crafting via the crafting_resource hook (slot 23).
 *
 * <p>When a player opens a workbench, the server discovers nearby chests to pool crafting
 * resources from. This mixin intercepts the crafting attempt to check if the player has
 * access to craft at the bench location.
 *
 * <p>Reads player UUID and bench coordinates from system-property-backed ThreadLocals
 * set by {@link BenchPositionCapture}. Uses bootstrap-class types only to avoid
 * cross-classloader issues.
 *
 * <p>Hook contract (crafting_resource slot):
 * <pre>
 *   boolean evaluateChestAccess(UUID player, String world, int chestX, int chestY, int chestZ,
 *                               int benchX, int benchY, int benchZ)
 *     Returns: true = allow chest, false = filter out
 * </pre>
 *
 * <p>Fail-open: no hook = all chests allowed.
 */
@Mixin(CraftingManager.class)
public class CraftingResourceFilter {

    @Unique
    private static final String CTX_PLAYER_KEY = "hyperprotect.ctx.craftingPlayerUuid";

    @Unique
    private static final String CTX_COORDS_KEY = "hyperprotect.ctx.benchCoords";

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            boolean.class, UUID.class, String.class,
            int.class, int.class, int.class,
            int.class, int.class, int.class);

    static {
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
            System.err.println("[HyperProtect] CraftingResourceFilter error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(23); // crafting_resource
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateChestAccess", EVALUATE_TYPE);
            cached = new Object[] { impl, primary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static UUID getCraftingPlayerUuid() {
        try {
            Object tl = System.getProperties().get(CTX_PLAYER_KEY);
            if (tl instanceof ThreadLocal<?>) {
                return (UUID) ((ThreadLocal<?>) tl).get();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static int[] getBenchCoords() {
        try {
            Object tl = System.getProperties().get(CTX_COORDS_KEY);
            if (tl instanceof ThreadLocal<?>) {
                return (int[]) ((ThreadLocal<?>) tl).get();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Inject at the head of isValidBenchForRecipe() to check if the player can access
     * the crafting bench resources. If the hook denies, returns false (bench not valid).
     *
     * <p>This prevents crafting when the bench uses resources from chests the player
     * shouldn't access, without needing to filter individual chests.
     */
    @Inject(
        method = "isValidBenchForRecipe",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void gateCraftingResources(CallbackInfoReturnable<Boolean> cir) {
        try {
            Object[] hook = resolveHook();
            if (hook == null) return;

            UUID playerUuid = getCraftingPlayerUuid();
            if (playerUuid == null) return;

            int[] coords = getBenchCoords();
            if (coords == null || coords.length < 3) return;

            // Get world name from context property
            String worldName = System.getProperty("hyperprotect.context.world", "");

            boolean allowed = (boolean) ((MethodHandle) hook[1]).invoke(
                    hook[0], playerUuid, worldName,
                    coords[0], coords[1], coords[2],
                    coords[0], coords[1], coords[2]);

            if (!allowed) {
                cir.setReturnValue(false); // Block crafting
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: allow crafting
        }
    }
}
