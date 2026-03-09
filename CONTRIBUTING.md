# Contributing to HyperProtect-Mixin

Thank you for your interest in contributing! This guide covers the architecture, build process, and conventions specific to HyperProtect-Mixin.

## Prerequisites

- **Java 25+** (source and target compatibility)
- **Gradle** (wrapper included)
- **Hytale Server** (Early Access) with `--accept-early-plugins` flag
- **Hyxin** early plugin loader (v0.0.11+)
- A consumer mod for testing hooks (e.g., [HyperFactions](https://github.com/HyperSystems-Development/HyperFactions))

## Architecture Overview

HyperProtect-Mixin is a **Hyxin-based early plugin** that injects protection checkpoints into the Hytale server via bytecode mixins.

```
Early Plugin Lifecycle → Hyxin Mixin Injection → Bridge Initialization
                                                        ↓
Consumer Mod (e.g., HyperFactions) → Registers Hook Objects at Slot Indices
                                                        ↓
Server Event Occurs → Mixin Interceptor Fires → Bridge Reads Hook → Verdict Returned
```

### Key Components

| Component | Location | Purpose |
|-----------|----------|---------|
| `ProtectionBridge` | `bridge/` | `AtomicReferenceArray<Object>(30)` stored in `System.getProperties()` — the cross-classloader bridge |
| `HookSlot` | `bridge/` | Eagerly-cached `MethodHandle` wrapper for hook implementations |
| `FaultReporter` | `bridge/` | Sampled error reporter (logs first + every 100th to prevent flooding) |
| `ChatFormatter` | `msg/` | `&`-code message formatter with hex color support |
| Interceptors | `intercept/` | Mixin classes organized by category (building, items, combat, etc.) |

### Bridge Design

- **No compile-time dependency** — consumers use `System.getProperties().get("hyperprotect.bridge")` to access the bridge
- **Reflection-only contracts** — hook methods are resolved via `MethodHandle` at runtime
- **One hook per slot** — last `bridge.set(index, ...)` wins
- **Fail-open** — errors and missing hooks always allow the action

## Building

HyperProtect-Mixin builds a **plain JAR** (no shadow/relocation — mixin JARs don't bundle dependencies):

```bash
./gradlew jar
```

Output: `build/libs/HyperProtect-Mixin-<version>.jar`

**Dependencies** (all `compileOnly`):
- `HytaleServer` — resolved from Maven (`maven.hytale.com`) or CurseMaven
- `Hyxin` — resolved from CurseMaven
- `org.jetbrains:annotations:24.1.0`

## Testing

1. Copy the built JAR to `earlyplugins/` (NOT `mods/`)
2. Ensure Hyxin is also in `earlyplugins/`
3. Add `--accept-early-plugins` to your server start script
4. Start the server and check logs for:
   - `HyperProtect-Mixin loaded!`
   - `Protection hooks: block-break, block-place, explosion, ...`
5. Verify system properties are set:
   - `hyperprotect.bridge.active` = `true`
   - `hyperprotect.intercept.*` properties for each interceptor
6. Test with a consumer mod (e.g., HyperFactions) to verify hooks fire correctly

## Adding a New Hook

### Step 1: Add a slot index

In `ProtectionBridge.java`, add a new constant:

```java
public static final int my_hook = 23; // Next available index
```

If the array size needs to increase, update `SLOT_COUNT`.

### Step 2: Create the mixin interceptor

Create a new class in the appropriate `intercept/` subdirectory:

```java
@Mixin(TargetClass.class)
public class MyHookInterceptor {
    @Unique private static final FaultReporter FAULTS = new FaultReporter("MyHookInterceptor");
    @Unique private static volatile HookSlot cachedSlot;

    @Unique private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);
    @Unique private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.my_hook", "true");
    }

    // ... resolveSlot(), formatReason(), and @Inject method
}
```

### Step 3: Register in mixin config

Add the class to `hyperprotect.mixin.json`:

```json
"com.hyperprotect.mixin.intercept.category.MyHookInterceptor"
```

### Step 4: Update documentation

- Add the hook to `docs/hooks.md` with method signatures and usage
- Add the slot to `docs/getting-started.md` slot table
- Add an example to `docs/examples.md`
- Add the system property to `docs/feature-detection.md`

### Conventions for interceptors

- Use `@Unique` for all static fields (avoids mixin class conflicts)
- Use volatile `HookSlot` caching with identity check on `impl()`
- Always wrap hook invocations in try/catch with `FAULTS.report(t)` — **fail-open**
- Set `InteractionState.Failed` + `ci.cancel()` for denied block interactions
- Set system property in `static {}` initializer for feature detection

## Code Style

- **Java 25 features** — records, sealed classes, pattern matching where appropriate
- **`@NotNull` / `@Nullable`** annotations on public API methods
- **`ConcurrentHashMap`** for shared mutable state
- **No raw types** — always use generics
- **Conventional naming** — `evaluateX` for primary methods, `fetchXDenyReason` for deny reason methods

## Commit Guidelines

Follow [Conventional Commits](https://www.conventionalcommits.org/):

- `feat:` — new hook, interceptor, or bridge feature
- `fix:` — bug fix
- `docs:` — documentation changes
- `refactor:` — code restructuring without behavior change
- `chore:` — build, dependency, or tooling updates

Examples:
```
feat: add hammer block cycling interceptor
fix: resolve race condition in HookSlot caching
docs: add respawn hook documentation
refactor: extract common interceptor pattern to base utility
```

## Branch Strategy

- **`main`** — stable releases
- **`feat/*`** — new hooks and features
- **`fix/*`** — bug fixes

## Reporting Issues

- **Bugs**: Use the [Bug Report](https://github.com/HyperSystems-Development/HyperProtect-Mixin/issues/new?template=BUG_REPORT.yml) template
- **Feature Requests**: Use the [Feature Request](https://github.com/HyperSystems-Development/HyperProtect-Mixin/issues/new?template=FEATURE_REQUEST.yml) template
- **Questions**: Join the [HyperSystems Discord](https://discord.com/invite/aZaa5vcFYh)
