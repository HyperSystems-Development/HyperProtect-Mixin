package com.hyperprotect.mixin.intercept.containers;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.builtin.adventure.shop.barter.BarterPage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts barter/trade execution in BarterPage.handleDataEvent() — slot 29.
 *
 * <p>No trade events exist. NPC barter trades execute without any hook, allowing
 * trading in restricted areas.
 *
 * <p>Hook contract (barter_trade slot):
 * <pre>
 *   int evaluateTrade(UUID player, String world, int x, int y, int z)
 *     Verdict: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT
 * </pre>
 *
 * <p>Fail-open: no hook = allow all trades.
 */
@Mixin(BarterPage.class)
public class BarterTradeInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.barter_trade", "true");
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
            System.err.println("[HyperProtect] BarterTradeInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(29); // barter_trade
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateTrade", EVALUATE_TYPE);
            MethodHandle secondary = null;
            try {
                secondary = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), "fetchTradeDenyReason", FETCH_REASON_TYPE);
            } catch (NoSuchMethodException ignored) {}
            cached = new Object[] { impl, primary, secondary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    /**
     * Inject at the head of handleDataEvent to check trade permission before execution.
     * Cancels the callback if the hook denies the trade.
     */
    @Inject(
        method = "handleDataEvent",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void gateTrade(Ref<EntityStore> ref, Store<EntityStore> store, Object eventData,
                           CallbackInfo ci) {
        try {
            Object[] hook = resolveHook();
            if (hook == null) return;

            // Get player UUID and position
            UUID playerUuid = null;
            String worldName = null;
            int x = 0, y = 0, z = 0;

            try {
                PlayerRef pRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (pRef != null) {
                    playerUuid = pRef.getUuid();
                    var transform = pRef.getTransform();
                    if (transform != null) {
                        var pos = transform.getPosition();
                        x = (int) pos.x;
                        y = (int) pos.y;
                        z = (int) pos.z;
                    }
                }
                var world = store.getExternalData().getWorld();
                if (world != null) {
                    worldName = world.getName();
                }
            } catch (Exception ignored) {}

            if (playerUuid == null || worldName == null) return;

            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                    hook[0], playerUuid, worldName, x, y, z);

            if (verdict < 0) return; // Fail-open

            if (verdict == 1 || verdict == 2) {
                if (verdict == 1 && hook.length > 2 && hook[2] != null) {
                    try {
                        String reason = (String) ((MethodHandle) hook[2]).invoke(
                                hook[0], playerUuid, worldName, x, y, z);
                        if (reason != null && !reason.isEmpty()) {
                            Object fmtHandle = getBridge(15);
                            if (fmtHandle instanceof MethodHandle mh) {
                                Player player = store.getComponent(ref, Player.getComponentType());
                                if (player != null) {
                                    Message msg = (Message) mh.invoke(reason);
                                    player.sendMessage(msg);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                ci.cancel(); // DENY — cancel trade execution
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: allow trade
        }
    }
}
