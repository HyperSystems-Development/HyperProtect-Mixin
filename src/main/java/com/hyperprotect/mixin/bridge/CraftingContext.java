package com.hyperprotect.mixin.bridge;

import java.util.UUID;

/**
 * Shared context for passing crafting player UUID and bench coordinates between
 * mixin-injected code and plugin-side code.
 *
 * <p>Delegates to system-property-backed ThreadLocals so that mixin classes
 * (which run in the server classloader) and plugin classes (which run in the
 * plugin classloader) both access the same ThreadLocal instances without
 * requiring cross-classloader class visibility.
 *
 * <p>Mixin classes must NOT import this class — they access the ThreadLocals
 * directly via {@code System.getProperties().get("hyperprotect.ctx.*")}.
 * This class exists for convenience in non-mixin (plugin-side) code.
 */
public final class CraftingContext {

    private static final String CTX_PLAYER_KEY = "hyperprotect.ctx.craftingPlayerUuid";
    private static final String CTX_COORDS_KEY = "hyperprotect.ctx.benchCoords";

    private CraftingContext() {}

    /** The UUID of the player currently opening a workbench (per-thread). */
    @SuppressWarnings("unchecked")
    public static ThreadLocal<UUID> craftingPlayerUuid() {
        return (ThreadLocal<UUID>) System.getProperties()
                .computeIfAbsent(CTX_PLAYER_KEY, k -> new ThreadLocal<>());
    }

    /** Bench coordinates captured during onOpen0 (per-thread). */
    @SuppressWarnings("unchecked")
    public static ThreadLocal<int[]> benchCoords() {
        return (ThreadLocal<int[]>) System.getProperties()
                .computeIfAbsent(CTX_COORDS_KEY, k -> new ThreadLocal<>());
    }
}
