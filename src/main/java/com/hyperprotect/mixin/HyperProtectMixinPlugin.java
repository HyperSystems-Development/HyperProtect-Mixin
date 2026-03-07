package com.hyperprotect.mixin;

import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hyperprotect.mixin.msg.ChatFormatter;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;

/**
 * Early plugin entry point for HyperProtect-Mixin.
 * Caches cross-classloader references and declares feature system properties.
 *
 * <p>Bridge initialization is handled by {@link HyperProtectConfigPlugin#onLoad(String)}
 * which runs earlier in the mixin lifecycle. This class handles setup that requires
 * the full plugin API (ChatFormatter handle caching, feature declarations).
 */
public class HyperProtectMixinPlugin extends JavaPlugin {

    public HyperProtectMixinPlugin(@NotNull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        super.setup();

        // Bridge is already initialized by HyperProtectConfigPlugin.onLoad().
        // Ensure it exists (defensive — in case onLoad wasn't called).
        AtomicReferenceArray<Object> bridge = ProtectionBridge.array();
        if (bridge == null) {
            bridge = ProtectionBridge.init();
            System.setProperty("hyperprotect.bridge.active", "true");
        }

        // Cache the ChatFormatter.format MethodHandle for cross-classloader access
        try {
            MethodHandle formatHandle = MethodHandles.publicLookup().findStatic(
                    ChatFormatter.class, "format",
                    MethodType.methodType(Message.class, String.class));
            bridge.set(ProtectionBridge.format_handle, formatHandle);
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("Failed to cache ChatFormatter handle: " + e.getMessage());
        }

        // Store classloader for fallback access
        System.getProperties().put("hyperprotect.bridge.loader", getClass().getClassLoader());

        // Mark bridge as active with version for updater detection
        String version = getClass().getPackage().getImplementationVersion();
        System.setProperty("hyperprotect.bridge.version", version != null ? version : "unknown");

        // Determine operating mode based on OrbisGuard detection from HyperProtectConfigPlugin.
        boolean compatMode = HyperProtectConfigPlugin.isOrbisGuardDetected();
        System.setProperty("hyperprotect.mode", compatMode ? "compatible" : "standalone");

        // Pre-declare intercepted features so consumer mods (HyperFactions) can detect
        // them during plugin setup.
        if (compatMode) {
            // Compatible mode: only declare features from the unique HP mixins
            System.setProperty("hyperprotect.intercept.block_place", "true");
            System.setProperty("hyperprotect.intercept.container_open", "true");
            System.setProperty("hyperprotect.intercept.entity_damage", "true");
            System.setProperty("hyperprotect.intercept.teleporter", "true");
            System.setProperty("hyperprotect.intercept.portal_entry", "true");
            System.setProperty("hyperprotect.intercept.portal_return", "true");
            System.setProperty("hyperprotect.intercept.hub_portal", "true");
            System.setProperty("hyperprotect.intercept.interaction_log", "true");
            System.setProperty("hyperprotect.intercept.hammer", "true");
            System.setProperty("hyperprotect.intercept.use", "true");
            System.setProperty("hyperprotect.intercept.crop_harvest", "true");
            System.setProperty("hyperprotect.intercept.seat", "true");
            System.setProperty("hyperprotect.intercept.respawn", "true");
            System.setProperty("hyperprotect.intercept.capture_crate_entity", "true");
            System.setProperty("hyperprotect.intercept.npc_use", "true");
            System.setProperty("hyperprotect.intercept.npc_contextual_use", "true");
            // New features (always active — OG has no equivalents)
            System.setProperty("hyperprotect.intercept.crafting_resource", "true");
            System.setProperty("hyperprotect.intercept.map_marker_filter", "true");
            System.setProperty("hyperprotect.intercept.fluid_spread", "true");
            System.setProperty("hyperprotect.intercept.prefab_spawn", "true");
            System.setProperty("hyperprotect.intercept.projectile_launch", "true");
            System.setProperty("hyperprotect.intercept.mount", "true");
            System.setProperty("hyperprotect.intercept.barter_trade", "true");
        } else {
            // Standalone mode: declare all features
            System.setProperty("hyperprotect.intercept.block_break", "true");
            System.setProperty("hyperprotect.intercept.block_place", "true");
            System.setProperty("hyperprotect.intercept.explosion", "true");
            System.setProperty("hyperprotect.intercept.fire_spread", "true");
            System.setProperty("hyperprotect.intercept.builder_tools", "true");
            System.setProperty("hyperprotect.intercept.item_pickup", "true");
            System.setProperty("hyperprotect.intercept.item_pickup_manual", "true");
            System.setProperty("hyperprotect.intercept.death_drop", "true");
            System.setProperty("hyperprotect.intercept.durability", "true");
            System.setProperty("hyperprotect.intercept.container_access", "true");
            System.setProperty("hyperprotect.intercept.container_open", "true");
            System.setProperty("hyperprotect.intercept.entity_damage", "true");
            System.setProperty("hyperprotect.intercept.spawn_marker", "true");
            System.setProperty("hyperprotect.intercept.world_spawn", "true");
            System.setProperty("hyperprotect.intercept.npc_spawn", "true");
            System.setProperty("hyperprotect.intercept.entity_load", "true");
            System.setProperty("hyperprotect.intercept.teleporter", "true");
            System.setProperty("hyperprotect.intercept.portal_entry", "true");
            System.setProperty("hyperprotect.intercept.portal_return", "true");
            System.setProperty("hyperprotect.intercept.instance_config", "true");
            System.setProperty("hyperprotect.intercept.instance_teleport", "true");
            System.setProperty("hyperprotect.intercept.instance_exit", "true");
            System.setProperty("hyperprotect.intercept.hub_portal", "true");
            System.setProperty("hyperprotect.intercept.command", "true");
            System.setProperty("hyperprotect.intercept.interaction_log", "true");
            System.setProperty("hyperprotect.intercept.hammer", "true");
            System.setProperty("hyperprotect.intercept.use", "true");
            System.setProperty("hyperprotect.intercept.crop_harvest", "true");
            System.setProperty("hyperprotect.intercept.seat", "true");
            System.setProperty("hyperprotect.intercept.respawn", "true");
            System.setProperty("hyperprotect.intercept.capture_crate_entity", "true");
            System.setProperty("hyperprotect.intercept.npc_use", "true");
            System.setProperty("hyperprotect.intercept.npc_contextual_use", "true");
            // New features
            System.setProperty("hyperprotect.intercept.crafting_resource", "true");
            System.setProperty("hyperprotect.intercept.map_marker_filter", "true");
            System.setProperty("hyperprotect.intercept.fluid_spread", "true");
            System.setProperty("hyperprotect.intercept.prefab_spawn", "true");
            System.setProperty("hyperprotect.intercept.projectile_launch", "true");
            System.setProperty("hyperprotect.intercept.mount", "true");
            System.setProperty("hyperprotect.intercept.barter_trade", "true");
        }

        if (compatMode) {
            getLogger().at(Level.INFO).log("HyperProtect-Mixin loaded! (COMPATIBLE mode — OrbisGuard detected)");
            getLogger().at(Level.INFO).log("Active HP hooks: block-place, entity-damage, container-open, " +
                    "use, hammer, seat, teleporter, portal, respawn, crop-harvest, " +
                    "capture-crate, npc-use, interaction-log, " +
                    "crafting-resource, map-marker, fluid-spread, prefab-spawn, " +
                    "projectile-launch, mount, barter-trade");
        } else {
            getLogger().at(Level.INFO).log("HyperProtect-Mixin loaded! (STANDALONE mode)");
            getLogger().at(Level.INFO).log("Protection hooks: block-break, block-place, explosion, entity-damage, " +
                    "auto-pickup, fire-spread, command, builder-tools, death-drop, durability, " +
                    "container-access, container-open, mob-spawn, teleporter, portal, " +
                    "hammer, use, seat, respawn, crop-harvest, capture-crate, npc-use, interaction-log, " +
                    "crafting-resource, map-marker, fluid-spread, prefab-spawn, " +
                    "projectile-launch, mount, barter-trade");
        }
    }
}
