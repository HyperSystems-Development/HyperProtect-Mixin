package com.hyperprotect.mixin.intercept.worldmap;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.providers.SharedMarkersProvider;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarkerComponent;
import com.hypixel.hytale.protocol.packets.worldmap.PlacedByMarkerComponent;
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
 * Filters shared map markers based on faction relationships via the map_marker_filter hook (slot 24).
 *
 * <p>{@code SharedMarkersProvider.update()} iterates all user-placed shared markers and sends
 * them to every player without filtering. This mixin redirects {@code collector.add(MapMarker)}
 * to check each marker's creator against the viewer's faction relationships before adding.
 *
 * <p>Uses the same bridge slot (24) as {@link MapMarkerFilter}, but calls a different method:
 * <pre>
 *   int filterSharedMarker(UUID viewer, UUID creator, String world, float x, float z)
 *     Verdict: 0 = SHOW, &gt;0 = HIDE
 * </pre>
 *
 * <p>Extracts creator UUID from the {@code PlacedByMarkerComponent} in the protocol marker's
 * components array, and position from {@code MapMarker.transform}.
 *
 * <p>Fail-open: no hook, no creator UUID, or error = show marker.
 *
 * <p>Uses {@code @Redirect} instead of {@code @Inject} to avoid referencing
 * {@code CallbackInfo} at runtime — the WorldMap thread's classloader does not
 * have Mixin library classes available.
 */
@Mixin(SharedMarkersProvider.class)
public class SharedMarkerFilter {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType FILTER_TYPE = MethodType.methodType(
            int.class, UUID.class, UUID.class, String.class, float.class, float.class);

    static {
        System.setProperty("hyperprotect.intercept.shared_marker_filter", "true");
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
            System.err.println("[HyperProtect] SharedMarkerFilter error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(24); // map_marker_filter — same slot, different method
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle mh = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), "filterSharedMarker", FILTER_TYPE);
            cached = new Object[] { impl, mh };
            hookCache = cached;
            return cached;
        } catch (NoSuchMethodException e) {
            // Hook doesn't implement filterSharedMarker — fail-open (show all)
            hookCache = null;
            return null;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    /**
     * Extracts the creator UUID from a MapMarker's PlacedByMarkerComponent.
     *
     * @return the creator UUID, or null if not present
     */
    @Unique
    private static UUID extractCreatorUuid(MapMarker marker) {
        MapMarkerComponent[] components = marker.components;
        if (components == null) return null;
        for (MapMarkerComponent component : components) {
            if (component instanceof PlacedByMarkerComponent placed) {
                return placed.playerId;
            }
        }
        return null;
    }

    /**
     * Redirect collector.add(MapMarker) to filter shared markers via the hook.
     * Each marker passes through the faction relationship check before being added.
     *
     * <p>The outer method params (World, Player, MarkersCollector) are appended
     * after the redirect target's params by Mixin.
     */
    @Redirect(
        method = "update",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/universe/world/worldmap/markers/MarkersCollector;add(Lcom/hypixel/hytale/protocol/packets/worldmap/MapMarker;)V"
        ),
        require = 0
    )
    private void filterSharedMarker(MarkersCollector collector, MapMarker marker,
                                     World world, Player player, MarkersCollector outerCollector) {
        try {
            Object[] hook = resolveHook();
            if (hook == null) {
                collector.add(marker); // No hook — show (original behavior)
                return;
            }

            UUID creatorUuid = extractCreatorUuid(marker);
            if (creatorUuid == null) {
                collector.add(marker); // Unknown creator — show (fail-open)
                return;
            }

            UUID viewerUuid = player.getUuid();
            if (viewerUuid == null || viewerUuid.equals(creatorUuid)) {
                collector.add(marker); // Self or unknown viewer — show
                return;
            }

            String worldName = world.getName();
            float x = 0f, z = 0f;
            try {
                if (marker.transform != null && marker.transform.position != null) {
                    x = (float) marker.transform.position.x;
                    z = (float) marker.transform.position.z;
                }
            } catch (Exception ignored) {}

            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                    hook[0], viewerUuid, creatorUuid, worldName, x, z);

            if (verdict <= 0) {
                collector.add(marker); // SHOW
            }
            // verdict > 0 = HIDE (don't add marker)
        } catch (Throwable t) {
            reportFault(t);
            collector.add(marker); // Fail-open: show marker
        }
    }
}
