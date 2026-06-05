package com.hyperprotect.mixin.intercept.building;

import com.hypixel.hytale.builtin.buildertools.BuilderToolsPacketHandler;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPasteClipboard;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts clipboard paste operations in BuilderToolsPacketHandler.
 *
 * <p>Hook contract (builder_tools slot):
 * <pre>
 *   int evaluatePaste(UUID playerUuid, String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES
 *
 *   String fetchPasteDenyReason(UUID playerUuid, String worldName, int x, int y, int z)
 *     Returns deny message or null (optional method)
 * </pre>
 */
@Mixin(BuilderToolsPacketHandler.class)
public abstract class PasteInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    static {
        System.setProperty("hyperprotect.intercept.builder_tools", "true");
    }

    // --- Helper methods ---

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
            System.err.println("[HyperProtect] PasteInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Message formatReason(String reason) {
        if (reason == null || reason.isEmpty()) return null;
        Object handle = getBridge(15); // format_handle = 15
        if (handle instanceof MethodHandle mh) {
            try { return (Message) mh.invoke(reason); } catch (Throwable ignored) {}
        }
        return Message.raw(reason);
    }

    /**
     * Redirect store.getComponent(ref, Player.getComponentType()) inside handleBuilderToolPasteClipboard.
     * The original method calls this early and null-checks the result — if null, returns immediately.
     * We perform our permission check here and return null to deny (triggering the existing early-return).
     */
    @Redirect(
        method = "handleBuilderToolPasteClipboard",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/Store;getComponent(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentType;)Lcom/hypixel/hytale/component/Component;",
            ordinal = 0)
    )
    private <S, T extends Component<S>> T gatePasteAction(Store<S> store, Ref<S> ref,
                                      com.hypixel.hytale.component.ComponentType<S, T> componentType,
                                      @Nonnull BuilderToolPasteClipboard packet,
                                      @Nonnull PlayerRef playerRef,
                                      @Nonnull Ref<EntityStore> entityRef,
                                      @Nonnull World world,
                                      @Nonnull Store<EntityStore> entityStore) {
        // Call the original method to get the Player component
        @SuppressWarnings("unchecked")
        T result = store.getComponent(ref, componentType);

        if (result == null) return null; // Let original null-check handle it

        try {
            Object[] hook = hookCache;

            // Re-resolve if hook changed or not yet cached
            Object current = getBridge(3); // builder_tools = 3
            if (hook == null || hook[0] != current) {
                if (current == null) return result; // No hook = allow
                try {
                    MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                        current.getClass(), "evaluatePaste",
                        MethodType.methodType(int.class,
                                UUID.class, String.class, int.class, int.class, int.class));
                    MethodHandle secondary = null;
                    try {
                        secondary = MethodHandles.publicLookup().findVirtual(
                            current.getClass(), "fetchPasteDenyReason",
                            MethodType.methodType(String.class,
                                    UUID.class, String.class, int.class, int.class, int.class));
                    } catch (NoSuchMethodException ignored) {}
                    hook = new Object[] { current, primary, secondary };
                    hookCache = hook;
                } catch (Exception e) {
                    reportFault(e);
                    return result;
                }
            }

            UUID playerUuid = playerRef.getUuid();
            String worldName = world.getName();

            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                    hook[0], playerUuid, worldName,
                    packet.x, packet.y, packet.z);

            // Fail-open for negative/unknown values
            if (verdict < 0) return result;

            switch (verdict) {
                case 0 -> { /* ALLOW */ }
                case 1 -> {
                    // DENY_WITH_MESSAGE — fetch reason and notify
                    if (hook.length >= 3 && hook[2] != null) {
                        String reason = (String) ((MethodHandle) hook[2]).invoke(
                                hook[0], playerUuid, worldName,
                                packet.x, packet.y, packet.z);
                        Message msg = formatReason(reason);
                        if (msg != null) playerRef.sendMessage(msg);
                    }
                    return null; // Return null to trigger early exit
                }
                case 2, 3 -> {
                    // DENY_SILENT or DENY_MOD_HANDLES — cancel silently
                    return null; // Return null to trigger early exit
                }
                default -> { /* Unknown positive = allow (fail-open) */ }
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: allow paste
        }
        return result;
    }
}
