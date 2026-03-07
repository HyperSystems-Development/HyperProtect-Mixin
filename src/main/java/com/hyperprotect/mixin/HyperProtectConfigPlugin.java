package com.hyperprotect.mixin;

import com.hyperprotect.mixin.bridge.ProtectionBridge;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin that detects OrbisGuard-Mixins at load time and
 * disables conflicting HyperProtect mixins to avoid {@code @Redirect} collisions.
 *
 * <p>When both HyperProtect-Mixin and OrbisGuard-Mixins are installed,
 * HP mixins that target the same call sites as OG are disabled.
 * The unique HP mixins (no OG equivalent) remain active.</p>
 *
 * <p>Detection runs once in {@link #onLoad(String)} using multi-signal detection:
 * system properties, bridge object, and JAR scan fallback.</p>
 */
public class HyperProtectConfigPlugin implements IMixinConfigPlugin {

    private static volatile boolean ogDetected = false;

    /**
     * The 14 mixin classes that have NO OrbisGuard equivalent and must always apply.
     * All other HP mixins conflict with OG and are disabled when OG is present.
     */
    private static final Set<String> SAFE_MIXINS = Set.of(
            "com.hyperprotect.mixin.intercept.interaction.SimpleBlockInteractionGate",
            "com.hyperprotect.mixin.intercept.interaction.SimpleInstantInteractionGate",
            "com.hyperprotect.mixin.intercept.interaction.CaptureCrateGate",
            "com.hyperprotect.mixin.intercept.combat.EntityDamageInterceptor",
            "com.hyperprotect.mixin.intercept.combat.ProjectileLaunchInterceptor",
            "com.hyperprotect.mixin.intercept.building.BlockPlaceInterceptor",
            "com.hyperprotect.mixin.intercept.building.FlameTickInterceptor",
            "com.hyperprotect.mixin.intercept.entities.RespawnInterceptor",
            "com.hyperprotect.mixin.intercept.entities.MountInterceptor",
            "com.hyperprotect.mixin.intercept.entities.PrefabSpawnInterceptor",
            "com.hyperprotect.mixin.intercept.containers.BenchPositionCapture",
            "com.hyperprotect.mixin.intercept.containers.CraftingResourceFilter",
            "com.hyperprotect.mixin.intercept.containers.BarterTradeInterceptor",
            "com.hyperprotect.mixin.intercept.worldmap.MapMarkerFilter"
    );

    @Override
    public void onLoad(String mixinPackage) {
        // Set presence marker early (before any plugin setup)
        System.setProperty("hyperprotect.mixins.active", "true");

        // Initialize bridge early so it's available when plugins load
        ProtectionBridge.init();
        System.setProperty("hyperprotect.bridge.active", "true");

        ogDetected = isOrbisGuardInstalled();

        if (ogDetected) {
            int safeCount = SAFE_MIXINS.size();
            int totalMixins = 29; // total mixins in json
            System.out.println("[HyperProtect] OrbisGuard-Mixins detected — "
                    + "disabling " + (totalMixins - safeCount) + " conflicting mixins, keeping " + safeCount + " unique mixins");
        } else {
            System.out.println("[HyperProtect] OrbisGuard-Mixins not detected — all mixins active");
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!ogDetected) {
            return true; // Standalone mode: apply all mixins
        }
        // Compatible mode: only apply the safe mixins
        return SAFE_MIXINS.contains(mixinClassName);
    }

    /**
     * Returns whether OrbisGuard-Mixins was detected at load time.
     * Called by {@link HyperProtectMixinPlugin} to set the mode system property.
     */
    public static boolean isOrbisGuardDetected() {
        return ogDetected;
    }

    /**
     * Multi-signal OrbisGuard detection.
     * Checks system properties first (fast), then falls back to JAR scanning.
     */
    private static boolean isOrbisGuardInstalled() {
        // Signal 1: System property set by OG mixin static initializers
        if ("true".equalsIgnoreCase(System.getProperty("orbisguard.mixins.loaded"))) return true;

        // Signal 2: Bridge object in system properties
        if (System.getProperties().get("orbisguard.bridge") != null) return true;

        // Signal 3: JAR scan fallback
        return scanEarlyPluginsDir();
    }

    /**
     * Scans earlyplugins/ for OrbisGuard-Mixins JAR files.
     */
    private static boolean scanEarlyPluginsDir() {
        try {
            Path serverDir = Path.of(System.getProperty("user.dir"));
            Path epDir = serverDir.resolve("earlyplugins");
            if (Files.isDirectory(epDir)) {
                try (var stream = Files.list(epDir)) {
                    return stream.anyMatch(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("OrbisGuard-Mixins") && name.endsWith(".jar");
                    });
                }
            }
        } catch (Exception e) {
            System.err.println("[HyperProtect] Failed to scan earlyplugins/ for OrbisGuard: " + e.getMessage());
        }
        return false;
    }

    // ========== Required IMixinConfigPlugin methods (defaults) ==========

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // no-op
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }
}
