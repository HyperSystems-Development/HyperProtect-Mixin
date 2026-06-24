package com.hyperprotect.mixin.intercept.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts command execution in CommandManager.handleCommand().
 * Only player commands are gated; console commands pass through unconditionally.
 *
 * <p>Hook contract (command slot):
 * <pre>
 *   int evaluateCommand(Player player, String commandString)
 *     Verdict: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES
 *
 *   String fetchCommandDenyReason(Player player, String commandString)
 *     Returns deny message or null (optional method)
 * </pre>
 */
@Mixin(CommandManager.class)
public abstract class CommandGateInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, Player.class, String.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, Player.class, String.class);

    static {
        System.setProperty("hyperprotect.intercept.command", "true");
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
            System.err.println("[HyperProtect] CommandGateInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(11);
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateCommand", EVALUATE_TYPE);
            MethodHandle secondary = null;
            try {
                secondary = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), "fetchCommandDenyReason", FETCH_REASON_TYPE);
            } catch (NoSuchMethodException ignored) {}
            cached = new Object[] { impl, primary, secondary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    @Unique
    private static void formatReason(Object[] hook, Player player, PlayerRef playerRef, String commandString) {
        try {
            if (hook.length < 3 || hook[2] == null) return;
            String raw = (String) ((MethodHandle) hook[2]).invoke(hook[0], player, commandString);
            if (raw == null || raw.isEmpty()) return;
            Object fmtHandle = getBridge(15);
            if (fmtHandle instanceof MethodHandle mh) {
                Message msg = (Message) mh.invoke(raw);
                playerRef.sendMessage(msg);
            }
        } catch (Throwable t) {
            reportFault(t);
        }
    }

    /**
     * Redirects the first Objects.requireNonNull(commandSender) call in handleCommand
     * to evaluate the command hook. If denied, stores a DeniedResult in a ThreadLocal
     * that the subsequent redirects (gateCommandFuture and gateCommandExecution) pick up
     * to short-circuit execution.
     *
     * <p>Uses three coordinated redirects because the requireNonNull result is discarded
     * by the caller — we can't prevent execution from this redirect alone. Instead:
     * <ol>
     *   <li>This redirect: evaluate hook, store denied flag</li>
     *   <li>gateCommandFuture: return pre-completed future when denied</li>
     *   <li>gateCommandExecution: skip ForkJoinPool.execute() when denied</li>
     * </ol>
     */
    @Redirect(
        method = "handleCommand(Lcom/hypixel/hytale/server/core/command/system/CommandSender;Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;",
        at = @At(value = "INVOKE",
            target = "Ljava/util/Objects;requireNonNull(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
            ordinal = 0)
    )
    private Object gateCommandDispatch(Object commandSender, String message,
                                        @Nonnull CommandSender sender,
                                        @Nonnull String commandString) {
        // Always perform the real null check first
        Objects.requireNonNull(commandSender, message);

        // Only gate player commands. In 0.5.3 the command sender is a PlayerRef
        // (Player is no longer a CommandSender); resolve the Player component so the
        // bridge hook contract evaluateCommand(Player, String) stays unchanged.
        if (!(sender instanceof PlayerRef playerRef)) {
            return commandSender; // Console / non-player senders pass through
        }
        Player player = playerRef.getComponent(Player.getComponentType());
        if (player == null) {
            return commandSender; // Can't resolve Player — fail-open
        }

        try {
            Object[] hook = resolveHook();
            if (hook == null) return commandSender;

            int verdict = (int) ((MethodHandle) hook[1]).invoke(hook[0], player, commandString);

            // Fail-open for negative/unknown values
            if (verdict < 0) return commandSender;

            switch (verdict) {
                case 0 -> { /* ALLOW */ }
                case 1 -> {
                    formatReason(hook, player, playerRef, commandString);
                    denied.set(new DeniedResult(CompletableFuture.completedFuture(null)));
                    return commandSender;
                }
                case 2, 3 -> {
                    denied.set(new DeniedResult(CompletableFuture.completedFuture(null)));
                    return commandSender;
                }
                default -> { /* Unknown positive = allow (fail-open) */ }
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: allow command
        }
        return commandSender;
    }

    @Unique
    private static final ThreadLocal<DeniedResult> denied = new ThreadLocal<>();

    @Unique
    private record DeniedResult(CompletableFuture<Void> future) {}

    /**
     * Redirect ForkJoinPool.commonPool().execute() to skip the Runnable when denied.
     */
    @Redirect(
        method = "handleCommand(Lcom/hypixel/hytale/server/core/command/system/CommandSender;Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;",
        at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ForkJoinPool;execute(Ljava/lang/Runnable;)V")
    )
    private void gateCommandExecution(java.util.concurrent.ForkJoinPool pool, Runnable task) {
        DeniedResult result = denied.get();
        if (result != null) {
            denied.remove();
            // Skip execution — command was denied
            return;
        }
        pool.execute(task);
    }

    /**
     * Redirect new CompletableFuture() to return the denied future when applicable.
     */
    @Redirect(
        method = "handleCommand(Lcom/hypixel/hytale/server/core/command/system/CommandSender;Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;",
        at = @At(value = "NEW",
            target = "java/util/concurrent/CompletableFuture")
    )
    private CompletableFuture<Void> gateCommandFuture() {
        DeniedResult result = denied.get();
        if (result != null) {
            // Don't remove yet — gateCommandExecution will clean up
            return result.future();
        }
        return new CompletableFuture<>();
    }
}
