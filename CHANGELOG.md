# Changelog

All notable changes to HyperProtect-Mixin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

*No changes yet*

## [1.1.0] - 2026-02-26

**Server Version:** `2026.02.19-1a311a592`

### Added
- **CaptureCrateGate** — new mixin interceptor for entity capture via UseCaptureCrateInteraction. Redirects `getTargetEntity()` in tick0() to check protection before allowing animal pickup. Uses bridge slot 20 (evaluateUse)
- **NPC tame/use interception** — added UseNPCInteraction and ContextualUseNPCInteraction to SimpleInstantInteractionGate HOOK_DEFS (slot 20, evaluateUse)
- CaptureCrateGate added to SAFE_MIXINS in HyperProtectConfigPlugin (6 unique mixins, was 5)
- System properties: `hyperprotect.intercept.capture_crate_entity`, `hyperprotect.intercept.npc_use`, `hyperprotect.intercept.npc_contextual_use`
- Pre-declared new interceptor properties in both standalone and compatible modes

### Fixed
- All 22 interceptors now log full stack traces on error (previously only printed exception class and message), making it possible to identify the exact line causing faults

## [1.0.0] - 2026-02-22

### Added

- **AtomicReferenceArray bridge** — 24-element lock-free bridge stored in `System.getProperties()` for zero-contention cross-classloader hook communication
- **20 protection hooks** across 7 categories:
  - **Building:** block_break (0), explosion (1), fire_spread (2), builder_tools (3), block_place (18), hammer (19), use (20)
  - **Items:** item_pickup (4), death_drop (5), durability (6)
  - **Containers:** container_access (7), container_open (17)
  - **Combat:** entity_damage (16)
  - **Entities:** mob_spawn (8), respawn (22)
  - **Transport:** teleporter (9), portal (10), seat (21)
  - **Commands:** command (11)
  - **Logging:** interaction_log (12)
- **22 mixin interceptors** covering all 20 hooks with multiple injection points for comprehensive coverage
- **Consolidated interaction gates** — `SimpleBlockInteractionGate` covers 20 `SimpleBlockInteraction` subclasses (block use, block state changes, hammer cycling, farming interactions, teleporters, portals, seating, minecarts, containers, crafting benches) via `HashMap<String, HookDef>` dispatch table; `SimpleInstantInteractionGate` covers 4 `SimpleInstantInteraction` subclasses (instance teleport, instance exit, hub portal, config instance)
- **OrbisGuard-Mixins compatibility mode** — `HyperProtectConfigPlugin` detects OrbisGuard-Mixins JARs in `earlyplugins/` at mixin load time and disables 17 conflicting HP mixins, keeping 5 unique mixins active. Sets `hyperprotect.mode` to `"compatible"` or `"standalone"` so consumer mods can adapt hook registration
- **Verdict protocol** — standardized ALLOW (0) / DENY_WITH_MESSAGE (1) / DENY_SILENT (2) / DENY_MOD_HANDLES (3)
- **Respawn value hook** — returns `double[3]` override coordinates instead of verdict int
- **Fail-open safety** — all hooks allow actions on error, missing hooks, or negative verdicts
- **System property detection** — `hyperprotect.bridge.active`, `hyperprotect.bridge.version`, `hyperprotect.mode`, and per-interceptor `hyperprotect.intercept.*` properties. Features pre-declared at plugin setup time so consumer mods can detect them during their own `setup()`
- **Spawn startup protection** — configurable spawn blocking during server initialization via `spawn_ready` and `spawn_allow_startup` flag slots
- **ChatFormatter** — token-based `&`-code message formatter with hex colors (`&#RRGGBB`), named colors, bold, italic, monospace, and reset support using sealed interfaces and records
- **FaultReporter** — sampled error logging (first + every 100th) to prevent log flooding
- **HookSlot caching** — eagerly-resolved MethodHandles with volatile identity-checked caching for minimal overhead
- **Deny message deduplication** — `SimpleBlockInteractionGate` applies 500ms per-player cooldown to prevent rapid-fire denial messages
- **JitPack publishing** — `maven-publish` plugin for consumer mods to depend via `com.github.HyperSystems-Development:HyperProtect-Mixin:1.0.0`
- **Build system** — Java 25 toolchain, Hytale server resolved from `maven.hytale.com`, `fileTree` glob for Hyxin libs, centralized version expansion in `manifest.json`
- **Complete documentation** — getting-started guide, hook reference, integration patterns, code examples, feature detection guide, and OrbisGuard migration guide
