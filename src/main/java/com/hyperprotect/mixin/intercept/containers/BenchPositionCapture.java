package com.hyperprotect.mixin.intercept.containers;

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

import java.util.UUID;

/**
 * Captures workbench block position context in BenchWindow.onOpen0().
 * Stores the bench coordinates in system-property-backed ThreadLocals so
 * {@link CraftingGateInterceptor} and {@link CraftingResourceFilter}
 * can use them for permission checks during crafting.
 *
 * <p>Context is stored via {@link System#getProperties()} (bootstrap classes only)
 * to avoid cross-classloader issues — mixin code runs in the server classloader,
 * which cannot see plugin classes.
 *
 * <p>This mixin does not perform hook checks — it only provides position context.
 * Fail-safe: any error is swallowed and the original setBench() always executes.
 */
@Mixin(BenchWindow.class)
public class BenchPositionCapture {

    @Unique
    private static final String CTX_PLAYER_KEY = "hyperprotect.ctx.craftingPlayerUuid";

    @Unique
    private static final String CTX_COORDS_KEY = "hyperprotect.ctx.benchCoords";

    static {
        System.setProperty("hyperprotect.intercept.workbench_context", "true");
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static ThreadLocal<UUID> playerUuidCtx() {
        return (ThreadLocal<UUID>) System.getProperties()
                .computeIfAbsent(CTX_PLAYER_KEY, k -> new ThreadLocal<>());
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static ThreadLocal<int[]> benchCoordsCtx() {
        return (ThreadLocal<int[]>) System.getProperties()
                .computeIfAbsent(CTX_COORDS_KEY, k -> new ThreadLocal<>());
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
        try {
            // Capture player UUID for CraftingResourceFilter
            UUID uuid = null;
            try {
                PlayerRef pRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (pRef != null) uuid = pRef.getUuid();
            } catch (Exception ignored) {}
            playerUuidCtx().set(uuid);

            // Store position for CraftingGateInterceptor and CraftingResourceFilter
            benchCoordsCtx().set(new int[]{x, y, z});
        } catch (Throwable ignored) {
            // Fail-safe: never let context capture prevent workbench opening
        }

        // Always call original — workbench must open regardless of context capture
        craftingManager.setBench(x, y, z, blockType);
    }
}
