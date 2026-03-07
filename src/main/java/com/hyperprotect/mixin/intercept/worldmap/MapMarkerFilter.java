package com.hyperprotect.mixin.intercept.worldmap;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.providers.OtherPlayersMarkerProvider;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Filters player markers on the world map via the map_marker_filter hook (slot 24).
 *
 * <p>World map marker creation has zero events — {@code OtherPlayersMarkerProvider.update()}
 * iterates all players and sends markers without any hook point. This mixin allows
 * protection plugins to hide enemy players on the map.
 *
 * <p>Hook contract (map_marker_filter slot):
 * <pre>
 *   int filterPlayerMarker(UUID viewer, UUID target, String world, int x, int y, int z)
 *     Verdict: 0 = SHOW, &gt;0 = HIDE
 * </pre>
 *
 * <p>Fail-open: no hook = show all markers.
 */
@Mixin(OtherPlayersMarkerProvider.class)
public class MapMarkerFilter {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, UUID.class, String.class, int.class, int.class, int.class);

    /** ThreadLocal to capture the viewing player UUID for each update() call. */
    @Unique
    private static final ThreadLocal<UUID> viewerUuid = new ThreadLocal<>();

    /** ThreadLocal to capture the world name for each update() call. */
    @Unique
    private static final ThreadLocal<String> worldNameLocal = new ThreadLocal<>();

    static {
        System.setProperty("hyperprotect.intercept.map_marker_filter", "true");
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
            System.err.println("[HyperProtect] MapMarkerFilter error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(24); // map_marker_filter
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "filterPlayerMarker", EVALUATE_TYPE);
            cached = new Object[] { impl, primary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    /**
     * Redirect player.getUuid() in update() to capture the viewing player UUID and world name.
     * The first getUuid() call in update() is for the self-check (viewer UUID).
     */
    @Redirect(
        method = "update",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/entity/entities/Player;getUuid()Ljava/util/UUID;",
            ordinal = 0
        ),
        require = 0
    )
    private UUID captureViewerUuid(Player player,
                                    World world, Player outerPlayer, MarkersCollector collector) {
        UUID uuid = player.getUuid();
        viewerUuid.set(uuid);
        if (world != null) {
            worldNameLocal.set(world.getName());
        }
        return uuid;
    }

    /**
     * Redirect collector.addIgnoreViewDistance() to filter markers via the hook.
     * Each marker added for another player passes through this check.
     * The marker ID format is "Player-{uuid}" which we parse to get the target UUID.
     */
    @Redirect(
        method = "update",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/universe/world/worldmap/markers/MarkersCollector;addIgnoreViewDistance(Lcom/hypixel/hytale/server/core/universe/world/worldmap/MapMarker;)V"
        ),
        require = 0
    )
    private void filterMarker(MarkersCollector collector, MapMarker marker) {
        try {
            Object[] hook = resolveHook();
            if (hook == null) {
                collector.addIgnoreViewDistance(marker);
                return;
            }

            UUID viewer = viewerUuid.get();
            if (viewer == null) {
                collector.addIgnoreViewDistance(marker);
                return;
            }

            // Extract target UUID from marker ID ("Player-{uuid}")
            UUID target = null;
            try {
                String markerId = marker.id;
                if (markerId != null && markerId.startsWith("Player-")) {
                    target = UUID.fromString(markerId.substring("Player-".length()));
                }
            } catch (Exception ignored) {}

            if (target == null || target.equals(viewer)) {
                collector.addIgnoreViewDistance(marker); // Self or unknown = show
                return;
            }

            // Get position from marker transform (public fields)
            String worldName = worldNameLocal.get();
            int x = 0, y = 0, z = 0;
            try {
                if (marker.transform != null && marker.transform.position != null) {
                    x = (int) marker.transform.position.x;
                    y = (int) marker.transform.position.y;
                    z = (int) marker.transform.position.z;
                }
            } catch (Exception ignored) {}

            if (worldName == null) {
                collector.addIgnoreViewDistance(marker);
                return;
            }

            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                    hook[0], viewer, target, worldName, x, y, z);

            if (verdict <= 0) {
                collector.addIgnoreViewDistance(marker); // SHOW
            }
            // verdict > 0 = HIDE (don't add marker)
        } catch (Throwable t) {
            reportFault(t);
            collector.addIgnoreViewDistance(marker); // Fail-open: show marker
        }
    }
}
