package com.hyperprotect.mixin.bridge;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Cross-classloader bridge for protection hooks.
 *
 * Stores an {@link AtomicReferenceArray} in {@link System#getProperties()} under
 * {@code "hyperprotect.bridge"}. Consumer mods place hook objects at specific slot
 * indices; interceptor (mixin) classes read from those slots at runtime.
 *
 * The array uses bootstrap-class types only (AtomicReferenceArray, Object) so it
 * works across classloader boundaries without custom class casting.
 */
public final class ProtectionBridge {

    private static final String BRIDGE_KEY = "hyperprotect.bridge";
    private static final int SLOT_COUNT = 30;

    // Slot indices — lowercase constants, same descriptive names as original hook keys
    public static final int block_break       = 0;
    public static final int explosion         = 1;
    public static final int fire_spread       = 2;
    public static final int builder_tools     = 3;
    public static final int item_pickup       = 4;
    public static final int death_drop        = 5;
    public static final int durability        = 6;
    public static final int container_access  = 7;
    public static final int mob_spawn         = 8;
    public static final int teleporter        = 9;
    public static final int portal            = 10;
    public static final int command           = 11;
    public static final int interaction_log   = 12;
    public static final int spawn_ready       = 13;  // Boolean flag
    public static final int spawn_allow_startup = 14; // Boolean flag
    public static final int format_handle     = 15;  // Cached MethodHandle for ChatFormatter
    public static final int entity_damage     = 16;
    public static final int container_open    = 17;
    public static final int block_place       = 18;
    public static final int hammer            = 19;
    public static final int use               = 20;
    public static final int seat              = 21;
    public static final int respawn           = 22;
    public static final int crafting_resource = 23;
    public static final int map_marker_filter = 24;
    public static final int fluid_spread      = 25;
    public static final int prefab_spawn      = 26;
    public static final int projectile_launch = 27;
    public static final int mount             = 28;
    public static final int barter_trade      = 29;

    private ProtectionBridge() {}

    /**
     * Creates the bridge array and stores it in system properties.
     * Called once during plugin {@code setup()}.
     */
    public static AtomicReferenceArray<Object> init() {
        AtomicReferenceArray<Object> array = new AtomicReferenceArray<>(SLOT_COUNT);
        System.getProperties().put(BRIDGE_KEY, array);
        return array;
    }

    /**
     * Returns the bridge array, or null if not yet initialized.
     */
    @SuppressWarnings("unchecked")
    public static AtomicReferenceArray<Object> array() {
        Object obj = System.getProperties().get(BRIDGE_KEY);
        return obj instanceof AtomicReferenceArray ? (AtomicReferenceArray<Object>) obj : null;
    }

    /**
     * Places a hook implementation at the given slot index.
     */
    public static void attach(int slot, Object impl) {
        AtomicReferenceArray<Object> arr = array();
        if (arr != null) arr.set(slot, impl);
    }

    /**
     * Removes the hook at the given slot index.
     */
    public static void detach(int slot) {
        AtomicReferenceArray<Object> arr = array();
        if (arr != null) arr.set(slot, null);
    }

    /**
     * Returns the hook object at the given slot, or null.
     */
    public static Object get(int slot) {
        AtomicReferenceArray<Object> arr = array();
        return arr != null ? arr.get(slot) : null;
    }

    /**
     * Whether a hook is attached at the given slot.
     */
    public static boolean hasBinding(int slot) {
        return get(slot) != null;
    }

    /**
     * Reads a boolean flag slot (spawn_ready, spawn_allow_startup).
     */
    public static boolean flag(int slot) {
        return Boolean.TRUE.equals(get(slot));
    }

    /**
     * Resolves a {@link HookSlot} for the given index with eagerly-cached MethodHandles.
     * Returns null if no hook is attached at that slot.
     *
     * @param slot         slot index
     * @param primaryName  name of the primary evaluation method
     * @param primaryType  MethodType of the primary method
     * @return resolved HookSlot, or null
     */
    public static HookSlot resolve(int slot,
                                   String primaryName,
                                   java.lang.invoke.MethodType primaryType) {
        return resolve(slot, primaryName, primaryType, null, null);
    }

    /**
     * Resolves a {@link HookSlot} with both primary and secondary (deny-reason) MethodHandles.
     * Returns null if no hook is attached at that slot.
     */
    public static HookSlot resolve(int slot,
                                   String primaryName,
                                   java.lang.invoke.MethodType primaryType,
                                   String secondaryName,
                                   java.lang.invoke.MethodType secondaryType) {
        Object impl = get(slot);
        if (impl == null) return null;
        return HookSlot.of(impl, primaryName, primaryType, secondaryName, secondaryType);
    }
}
