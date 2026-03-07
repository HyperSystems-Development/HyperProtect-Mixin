package com.hyperprotect.mixin.intercept.worldmap;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.providers.SharedMarkersProvider;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.user.UserMapMarker;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.worldstore.WorldMarkersResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Filters shared map markers based on faction relationships via the map_marker_filter hook (slot 24).
 *
 * <p>{@code SharedMarkersProvider.update()} iterates all user-placed shared markers and sends
 * them to every player without filtering. This mixin replaces the method to check each marker's
 * creator against the viewer's faction relationships before adding it to the collector.
 *
 * <p>Uses the same bridge slot (24) as {@link MapMarkerFilter}, but calls a different method:
 * <pre>
 *   int filterSharedMarker(UUID viewer, UUID creator, String world, float x, float z)
 *     Verdict: 0 = SHOW, &gt;0 = HIDE
 * </pre>
 *
 * <p>Fail-open: no hook or no creator UUID = show marker.
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
     * Inject at HEAD of update() to replace the loop with a filtered version.
     * Cancels the original method and reimplements the marker iteration with hook checks.
     */
    @Inject(method = "update", at = @At("HEAD"), cancellable = true, require = 0)
    private void filterSharedMarkers(World world, Player player, MarkersCollector collector,
                                     CallbackInfo ci) {
        try {
            Object[] hook = resolveHook();

            WorldMarkersResource resource = world.getChunkStore().getStore()
                    .getResource(WorldMarkersResource.getResourceType());
            Collection<? extends UserMapMarker> markers = resource.getUserMapMarkers();

            if (hook == null) {
                // No hook — show all markers (original behavior)
                for (UserMapMarker marker : markers) {
                    collector.add(marker.toProtocolMarker());
                }
                ci.cancel();
                return;
            }

            UUID viewerUuid = player.getUuid();
            String worldName = world.getName();

            for (UserMapMarker marker : markers) {
                try {
                    UUID creatorUuid = marker.getCreatedByUuid();
                    if (creatorUuid == null) {
                        collector.add(marker.toProtocolMarker()); // Unknown creator — show
                        continue;
                    }

                    int verdict = (int) ((MethodHandle) hook[1]).invoke(
                            hook[0], viewerUuid, creatorUuid, worldName,
                            marker.getX(), marker.getZ());

                    if (verdict <= 0) {
                        collector.add(marker.toProtocolMarker()); // SHOW
                    }
                    // verdict > 0 = HIDE (don't add marker)
                } catch (Throwable t) {
                    reportFault(t);
                    collector.add(marker.toProtocolMarker()); // Fail-open
                }
            }

            ci.cancel();
        } catch (Throwable t) {
            reportFault(t);
            // Don't cancel — let original method run as fallback
        }
    }
}
