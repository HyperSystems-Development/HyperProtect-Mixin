package com.hyperprotect.mixin.intercept.containers;

import com.hyperprotect.mixin.bridge.CraftingContext;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.builtin.crafting.window.BenchWindow;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Captures workbench block position context in BenchWindow.onOpen0().
 * Stores the bench coordinates in a ThreadLocal so {@link CraftingGateInterceptor}
 * can use them for container_access permission checks during crafting.
 *
 * <p>This mixin does not perform hook checks — it only provides position context.
 */
@Mixin(BenchWindow.class)
public class BenchPositionCapture {

    /** ThreadLocal bench coordinates for use by CraftingGateInterceptor. */
    @Unique
    private static final ThreadLocal<int[]> benchCoords = new ThreadLocal<>();

    static {
        System.setProperty("hyperprotect.intercept.workbench_context", "true");
    }

    /**
     * Redirect the setBench call to capture block position and player UUID before it executes.
     * The enclosing method params (ref, store) provide access to the player entity.
     */
    @Redirect(
        method = "onOpen0",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/builtin/crafting/component/CraftingManager;setBench(IIILcom/hypixel/hytale/server/core/asset/type/blocktype/config/BlockType;)V"
        )
    )
    private void captureBenchCoords(CraftingManager craftingManager, int x, int y, int z,
                                    BlockType blockType,
                                    Ref<EntityStore> ref, Store<EntityStore> store) {
        // Capture player UUID for CraftingResourceFilter
        try {
            PlayerRef pRef = store.getComponent(ref, PlayerRef.getComponentType());
            CraftingContext.craftingPlayerUuid.set(pRef != null ? pRef.getUuid() : null);
        } catch (Exception ignored) {
            CraftingContext.craftingPlayerUuid.set(null);
        }

        // Store position for CraftingGateInterceptor and CraftingResourceFilter
        int[] coords = new int[]{x, y, z};
        benchCoords.set(coords);
        CraftingContext.benchCoords.set(coords);

        // Call original
        craftingManager.setBench(x, y, z, blockType);
    }
}
