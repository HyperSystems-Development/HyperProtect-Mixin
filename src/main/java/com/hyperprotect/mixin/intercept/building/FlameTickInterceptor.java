package com.hyperprotect.mixin.intercept.building;

import com.hypixel.hytale.server.core.asset.type.blocktick.BlockTickStrategy;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.fluid.FluidTicker;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts fire fluid spreading in {@code FluidTicker.process()}.
 *
 * <p>When the fire hook returns a deny verdict, the fire fluid is removed and the
 * tick returns {@link BlockTickStrategy#SLEEP} to halt propagation.
 *
 * <p>Hook contract (fire_spread slot):
 * <ul>
 *   <li>Primary: {@code int evaluateFlame(String, int, int, int)} -- returns verdict</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW (let fire spread), any positive=DENY (block fire spread, no messaging).
 * Negative/unknown values = ALLOW (fail-open).
 */
@Mixin(FluidTicker.class)
public abstract class FlameTickInterceptor {

    // --- Verdict constant ---

    @Unique private static final int ALLOW = 0;

    // --- Fault tracking ---

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    // --- Cached hooks (volatile for cross-thread visibility) ---

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static volatile Object[] fluidHookCache;

    // --- MethodType for hook resolution ---

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.fire_spread", "true");
    }

    @Shadow
    protected abstract BlockTickStrategy spread(World world, long tick, FluidTicker.Accessor accessor,
                                                FluidSection fluidSection, BlockSection blockSection,
                                                Fluid fluid, int fluidId, byte fluidLevel,
                                                int worldX, int worldY, int worldZ);

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
            System.err.println("[HyperProtect] FlameTickInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    // --- Hook resolution ---

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(2); // fire_spread = 2
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateFlame", EVALUATE_TYPE);
            cached = new Object[] { impl, primary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    // --- Injection point ---

    @Redirect(
        method = "process",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/asset/type/fluid/FluidTicker;spread(Lcom/hypixel/hytale/server/core/universe/world/World;JLcom/hypixel/hytale/server/core/asset/type/fluid/FluidTicker$Accessor;Lcom/hypixel/hytale/server/core/universe/world/chunk/section/FluidSection;Lcom/hypixel/hytale/server/core/universe/world/chunk/section/BlockSection;Lcom/hypixel/hytale/server/core/asset/type/fluid/Fluid;IBIII)Lcom/hypixel/hytale/server/core/asset/type/blocktick/BlockTickStrategy;"
        ),
        require = 0
    )
    private BlockTickStrategy onSpread(FluidTicker self, World world, long tick,
                                       FluidTicker.Accessor accessor, FluidSection fluidSection,
                                       BlockSection blockSection, Fluid fluid, int fluidId,
                                       byte fluidLevel, int worldX, int worldY, int worldZ) {

        if (isFlameSource(self) && queryFlameVerdict(world, worldX, worldY, worldZ)) {
            fluidSection.setFluid(worldX, worldY, worldZ, 0, (byte) 0);
            return BlockTickStrategy.SLEEP;
        }

        // Water/lava spread check (slot 25)
        if (!isFlameSource(self) && queryFluidVerdict(world, worldX, worldY, worldZ)) {
            fluidSection.setFluid(worldX, worldY, worldZ, 0, (byte) 0);
            return BlockTickStrategy.SLEEP;
        }

        return this.spread(world, tick, accessor, fluidSection, blockSection,
                fluid, fluidId, fluidLevel, worldX, worldY, worldZ);
    }

    /**
     * Checks if the ticker is a fire-type fluid ticker.
     * Uses {@code contains("Fire")} for broader matching than exact class name equality.
     */
    @Unique
    private static boolean isFlameSource(FluidTicker ticker) {
        return ticker.getClass().getSimpleName().contains("Fire");
    }

    /**
     * Queries the fire spread hook for a verdict.
     *
     * @return true if fire spread should be blocked, false to allow
     */
    @Unique
    private static boolean queryFlameVerdict(World world, int x, int y, int z) {
        try {
            Object[] hook = resolveHook();
            if (hook == null) return false; // No hook = allow (fail-open)

            String worldName = world.getName();
            int verdict = (int) ((MethodHandle) hook[1]).invoke(hook[0], worldName, x, y, z);

            // Any positive verdict = block fire spread
            return verdict > ALLOW;
        } catch (Throwable e) {
            reportFault(e);
            return false; // Fail-open
        }
    }

    // --- Fluid spread hook (slot 25) ---

    @Unique
    private static Object[] resolveFluidHook() {
        Object[] cached = fluidHookCache;
        Object impl = getBridge(25); // fluid_spread
        if (impl == null) {
            fluidHookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateFluidSpread", EVALUATE_TYPE);
            cached = new Object[] { impl, primary };
            fluidHookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    /**
     * Queries the fluid spread hook for a verdict.
     *
     * @return true if fluid spread should be blocked, false to allow
     */
    @Unique
    private static boolean queryFluidVerdict(World world, int x, int y, int z) {
        try {
            Object[] hook = resolveFluidHook();
            if (hook == null) return false; // No hook = allow (fail-open)

            String worldName = world.getName();
            int verdict = (int) ((MethodHandle) hook[1]).invoke(hook[0], worldName, x, y, z);

            return verdict > ALLOW;
        } catch (Throwable e) {
            reportFault(e);
            return false; // Fail-open
        }
    }
}
