package com.hyperprotect.mixin.intercept.building;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTool;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts explosion block damage in BlockHarvestUtils.performBlockDamage().
 * When the damage source has no entity (entity == null AND ref == null), it's an explosion.
 *
 * <p>Hook contract (explosion slot):
 * <pre>
 *   int evaluateExplosion(World world, int x, int y, int z)
 *     Verdict: 0=ALLOW, 2=DENY_SILENT
 * </pre>
 *
 * <p>No player context — explosions are environmental. No messaging needed.
 */
@Mixin(BlockHarvestUtils.class)
public class ExplosionInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    static {
        System.setProperty("hyperprotect.intercept.explosion", "true");
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
            System.err.println("[HyperProtect] ExplosionInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Redirect(
        method = "performBlockDamage(Lorg/joml/Vector3i;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Lcom/hypixel/hytale/server/core/asset/type/item/config/ItemTool;FIZLcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;Lcom/hypixel/hytale/component/ComponentAccessor;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/modules/interaction/BlockHarvestUtils;performBlockDamage(Lcom/hypixel/hytale/component/Ref;Lorg/joml/Vector3i;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Lcom/hypixel/hytale/server/core/asset/type/item/config/ItemTool;Ljava/lang/String;ZFIZLcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;Lcom/hypixel/hytale/component/ComponentAccessor;)Z"
        )
    )
    private static boolean redirectExplosionBlockDamage(
            @Nullable Ref<EntityStore> ref,
            @Nonnull Vector3i targetBlockPos,
            @Nullable ItemStack itemStack,
            @Nullable ItemTool tool,
            @Nullable String toolId,
            boolean matchTool,
            float damageScale,
            int setBlockSettings,
            boolean isExplosion,
            @Nonnull Ref<ChunkStore> chunkReference,
            @Nonnull ComponentAccessor<EntityStore> entityStore,
            @Nonnull ComponentAccessor<ChunkStore> chunkStore) {

        // On 0.6.0-pre.4 both performBlockDamage overloads gained a trailing boolean
        // isExplosion (outer/sourceless 8->9 args, inner 11->12 args). The outer convenience
        // overload this redirect lives in still hard-codes ref=null when delegating to the
        // inner impl, so a null ref still identifies an explosion-style block break with no
        // entity source. The captured isExplosion is forwarded to the inner call unchanged
        // on the allow path.
        if (ref == null) {
            try {
                int verdict = queryExplosionVerdict(entityStore, targetBlockPos);
                if (verdict != 0) {
                    return false; // Block the explosion damage
                }
            } catch (Throwable t) {
                reportFault(t);
                // Fail-open: allow explosion
            }
        }

        return BlockHarvestUtils.performBlockDamage(
                ref, targetBlockPos, itemStack, tool, toolId,
                matchTool, damageScale, setBlockSettings, isExplosion, chunkReference,
                entityStore, chunkStore);
    }

    /**
     * Evaluates whether an explosion at the given block position should be allowed.
     *
     * @return verdict int: 0=ALLOW, non-zero=DENY
     */
    @Unique
    private static int queryExplosionVerdict(ComponentAccessor<EntityStore> entityStore,
                                             Vector3i targetBlockPos) throws Throwable {
        Object[] cached = hookCache;

        // Re-resolve if hook changed or not yet cached
        Object current = getBridge(1); // explosion = 1
        if (cached == null || cached[0] != current) {
            if (current == null) return 0; // No hook = allow
            try {
                MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                    current.getClass(), "evaluateExplosion",
                    MethodType.methodType(int.class, World.class, int.class, int.class, int.class));
                cached = new Object[] { current, primary };
                hookCache = cached;
            } catch (Exception e) {
                reportFault(e);
                return 0;
            }
        }

        World world = ((EntityStore) entityStore.getExternalData()).getWorld();
        if (world == null) return 0;

        int verdict = (int) ((MethodHandle) cached[1]).invoke(
                cached[0], world,
                targetBlockPos.x(), targetBlockPos.y(), targetBlockPos.z());

        // Fail-open for negative/unknown values
        return verdict < 0 ? 0 : verdict;
    }
}
