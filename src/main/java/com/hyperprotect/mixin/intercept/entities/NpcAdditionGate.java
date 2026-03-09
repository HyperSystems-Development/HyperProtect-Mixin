package com.hyperprotect.mixin.intercept.entities;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts NPC entity addition via Store.addEntity() within NPCPlugin.spawnEntity().
 * Checks the mob_spawn hook before allowing NPC entities to be added.
 *
 * <p>Only intercepts {@link AddReason#SPAWN} — LOAD and other reasons pass through.
 * Extracts actual spawn position from the holder's TransformComponent.
 */
@Mixin(NPCPlugin.class)
public class NpcAdditionGate {

    @Unique
    private static final String BRIDGE_KEY = "hyperprotect.bridge";
    @Unique
    private static final int SPAWN_SLOT = 8;
    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0L);
    @Unique
    private static volatile MethodHandle cachedCheckHandle;
    @Unique
    private static volatile Object cachedBridge;

    @Unique
    private static AtomicReferenceArray<Object> getBridge() {
        Object bridge = System.getProperties().get(BRIDGE_KEY);
        if (bridge instanceof AtomicReferenceArray) {
            return (AtomicReferenceArray<Object>) bridge;
        }
        return null;
    }

    @Redirect(
        method = "spawnEntity(Lcom/hypixel/hytale/component/Store;ILcom/hypixel/hytale/math/vector/Vector3d;Lcom/hypixel/hytale/math/vector/Vector3f;Lcom/hypixel/hytale/server/core/asset/type/model/config/Model;Lcom/hypixel/hytale/function/consumer/TriConsumer;Lcom/hypixel/hytale/function/consumer/TriConsumer;)Lit/unimi/dsi/fastutil/Pair;",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/Store;addEntity(Lcom/hypixel/hytale/component/Holder;Lcom/hypixel/hytale/component/AddReason;)Lcom/hypixel/hytale/component/Ref;"
        ),
        require = 0
    )
    private Ref<EntityStore> gateNpcAddition(Store<EntityStore> store, Holder<EntityStore> holder, AddReason reason) {
        if (reason == AddReason.SPAWN) {
            TransformComponent transform = holder.getComponent(TransformComponent.getComponentType());
            if (transform != null) {
                Vector3d position = transform.getPosition();
                String worldName = NpcAdditionGate.resolveWorldName(store);
                if (worldName != null && NpcAdditionGate.querySpawnHook(worldName, position)) {
                    return null;
                }
            }
        }
        return store.addEntity(holder, reason);
    }

    @Unique
    private static String resolveWorldName(Store<EntityStore> store) {
        try {
            return store.getExternalData().getWorld().getName();
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private static boolean querySpawnHook(String worldName, Vector3d position) {
        try {
            AtomicReferenceArray<Object> bridge = NpcAdditionGate.getBridge();
            if (bridge == null) {
                return false;
            }
            Object hook = bridge.get(SPAWN_SLOT);
            if (hook == null) {
                return false;
            }
            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (NpcAdditionGate.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedCheckHandle = lookup.findVirtual(
                            hook.getClass(), "evaluateCreatureSpawn",
                            MethodType.methodType(int.class, String.class, int.class, int.class, int.class)
                        );
                        cachedBridge = bridge;
                    }
                }
            }
            int x = (int) Math.floor(position.getX());
            int y = (int) Math.floor(position.getY());
            int z = (int) Math.floor(position.getZ());
            int verdict = (int) cachedCheckHandle.invoke(hook, worldName, x, y, z);
            return verdict > 0;
        } catch (Throwable t) {
            long count = errorCount.incrementAndGet();
            if (count == 1L || count % 100L == 0L) {
                System.err.println("[HyperProtect] NpcAdditionGate error #" + count + ": " + t);
                t.printStackTrace(System.err);
            }
            return false;
        }
    }
}
