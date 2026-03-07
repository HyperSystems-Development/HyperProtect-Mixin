package com.hyperprotect.mixin.bridge;

import java.util.UUID;

/**
 * Shared context for passing crafting player UUID between mixins.
 * BenchPositionCapture sets the UUID; CraftingResourceFilter reads it.
 *
 * <p>This is a plain class (not a mixin) so it can hold public static fields
 * that Mixin's visibility rules would reject inside a mixin class.
 */
public final class CraftingContext {

    private CraftingContext() {}

    /** The UUID of the player currently opening a workbench (per-thread). */
    public static final ThreadLocal<UUID> craftingPlayerUuid = new ThreadLocal<>();

    /** Bench coordinates captured during onOpen0 (per-thread). */
    public static final ThreadLocal<int[]> benchCoords = new ThreadLocal<>();
}
