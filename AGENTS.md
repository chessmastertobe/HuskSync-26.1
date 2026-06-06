# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

> **Fork note:** This is an unofficial fork of [HuskSync by William278](https://github.com/WiIIiam278/HuskSync) (upstream v3.8.8). The primary addition is Paper **26.1.2** (Minecraft's new versioning scheme) + **Java 25** support. All upstream Bukkit 1.21.x and Fabric targets are preserved.

## Build Commands

```bash
# Build all modules; outputs to /target (Gradle Toolchain auto-downloads required JDK)
./gradlew clean build

# licenseFormat is part of the default task — run it before committing new .java files
./gradlew licenseFormat

# Run tests only
./gradlew test

# Run a single test class
./gradlew test --tests "net.william278.husksync.LocalesTests"
```

Builds require Java 25 at the Gradle level (the 26.1.2 Bukkit target uses Java 25; earlier Bukkit targets use Java 21; Gradle Toolchain auto-configures the required JDK per submodule). Outputs are per-platform, per-Minecraft-version JARs named `HuskSync-{Platform}-{version}+mc.{mc_version}.jar` in `/target/`.

**CI note:** `pr_tests.yml` runs `gradle test` on JDK 21 only. `ci.yml` (push to master) runs `build test publish` on JDK 25 and publishes artifacts to the alpha channel.

**Artifact naming:** If the repo has uncommitted changes at build time, artifacts are suffixed `-{gitHash}-indev` (e.g., `HuskSync-Bukkit-3.8.8.5+mc.26.1.2-abc1234-indev.jar`).

## Project Overview

HuskSync is a multi-server Minecraft player data synchronization plugin. It uses Redis for fast inter-server caching and MySQL/MariaDB/PostgreSQL/MongoDB for persistent storage. A player switching servers triggers a Redis-based sync pipeline rather than a direct database read.

## Module Structure

This is a multi-module Gradle project:

- `**common/`** — Platform-agnostic core logic; the only module with tests
- `**bukkit/{version}/**` — Paper/Spigot implementations for MC 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11, **26.1.2** (this fork's addition); all version submodules share source under `bukkit/src/`
- `**fabric/{version}/`** — Fabric mod implementations (1.21.1, 1.21.4, 1.21.5, 1.21.8)

## Core Architecture

### Data Flow

1. Player switches servers → source server saves `DataSnapshot` to Redis via `RedisManager`
2. Destination server's `DataSyncer` polls Redis for the snapshot
3. On receipt, snapshot is applied to the player via `UserDataHolder.applySnapshot()`
4. Snapshot is also persisted to the database asynchronously

### Key Abstractions


| Interface/Class      | Role                                                                                    |
| -------------------- | --------------------------------------------------------------------------------------- |
| `HuskSync`           | Central plugin interface; Bukkit and Fabric each implement this                         |
| `Data`               | Interface for a single syncable data type (inventory, health, etc.)                     |
| `DataSnapshot`       | Versioned container holding multiple `Data` instances for one player                    |
| `DataHolder`         | Holds a map of `Identifier → Data`; snapshots and online users both implement this      |
| `UserDataHolder`     | Extends `DataHolder` for online players; knows how to apply/capture live data           |
| `DataSyncer`         | Abstract sync coordinator; concrete subclasses: `LockstepDataSyncer`, `DelayDataSyncer` |
| `Database`           | Abstract database layer; implementations: MySQL, MariaDB, PostgreSQL, MongoDB           |
| `RedisManager`       | Manages Redis pub/sub and key-value caching via Jedis                                   |
| `SerializerRegistry` | Registry of `Serializer<T>` implementations for each `Data` subtype                     |


### Sync Modes

Configured via `Settings.SynchronizationSettings.syncMode`:

- **Lockstep** (`LockstepDataSyncer`) — source server sets a `DATA_CHECKOUT` mutex in Redis; destination polls until checkout is released; default and recommended
- **Delay** (`DelayDataSyncer`) — sets a `SERVER_SWITCH` flag (10 s TTL), then destination applies data after a configurable network-latency delay; faster but relies on network reliability

### Redis Key Types

All keys use the format `husksync:<clusterId>:<keyType>:<uuid>`:


| Key type                                  | Purpose                                  | TTL        |
| ----------------------------------------- | ---------------------------------------- | ---------- |
| `LATEST_SNAPSHOT`                         | Packed player snapshot                   | 7 days     |
| `SERVER_SWITCH`                           | Delay-mode server-switch flag            | 10 seconds |
| `DATA_CHECKOUT`                           | Lockstep mutex (which server holds data) | None       |
| `MAP_ID` / `MAP_ID_REVERSED` / `MAP_DATA` | Cross-server map ID translation cache    | 1 year     |


### Multi-Version Strategy

Both platforms use the same preprocessor comment syntax. The `MC` variable is set per-submodule via `gradle.properties`.

**MC numeric values:**


| Submodule | MC value |
| --------- | -------- |
| 1.21.1    | `12101`  |
| 1.21.4    | `12104`  |
| 1.21.5    | `12105`  |
| 1.21.8    | `12108`  |
| 1.21.10   | `12110`  |
| 1.21.11   | `12111`  |
| 26.1.2    | `260102` |


**Preprocessor syntax** (used in both Bukkit and Fabric source files):

```java
//#if MC>=12104
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences;
//#else
//$$ import net.kyori.adventure.platform.fabric.FabricServerAudiences;
//#endif
```

- Active code: written normally
- Inactive/else branch: each line prefixed with `//$$` 
- Conditions: `>=`, `==`, `<=`, `>`, `<` against the numeric MC value

**Bukkit:** Uses `net.william278.preprocessor` Gradle plugin.  
**Fabric:** Uses `gg.essential.multi-version` — separate source sets per version with a shared root; same preprocessor comment syntax applies.

## API & Events

`**HuskSyncAPI`** (`common/.../api/HuskSyncAPI.java`) — static `getInstance()` entry point for external plugins. Provides async user lookup, snapshot read/write, pin/unpin, custom serializer registration, and runtime `DataSyncer` replacement.

`**EventDispatcher**` — fired by `DataSyncer` at key sync points:

- `PreSyncEvent` — cancellable; fired before applying a snapshot to an online player
- `SyncCompleteEvent` — fired after snapshot application succeeds
- `DataSaveEvent` — fired whenever a snapshot is persisted to the database

`**DataSnapshot` pinning** — snapshots can be pinned to exempt them from rotation. `SaveCause` enum values (e.g., `SERVER_SWITCH`, `DISCONNECT`, `API`) are stored with each snapshot and determine auto-pin behaviour via `auto_pinned_save_causes` config.

**Data migration** — `LegacyMigrator` and `MpdbMigrator` are registered via `HuskSync.getAvailableMigrators()` for importing data from older formats.

## Test Structure

Tests live in `common/src/test/java/net/william278/husksync/`:

- `LocalesTests` — validates all locale keys are present and parseable
- `PlanHookTests` — tests Plan analytics integration
- `CompatibilityCheckerTests` — validates version compatibility matrix logic

For full end-to-end network testing, `test/spin_network.py` spins up a Velocity proxy + multiple Paper servers automatically. Requires Windows, Python 3.14, and running MySQL and Redis instances.

## Adding Dependencies (Bukkit)

`bukkit/build.gradle` uses `shadowJar` to bundle libraries and relocates them to `net.william278.husksync.libraries.*` to avoid classpath conflicts in plugin environments. When adding a new dependency:

- Add it to `bukkit/build.gradle` under `dependencies`
- Add a `relocate` rule in the `shadowJar` block if it's a new top-level package
- **Paper 26.1.x only:** runtime-loaded libraries (DB drivers, Jedis) are declared in `bukkit/src/main/resources/paper-libraries.yml` instead of being shaded. Paper injects these at startup. Versions are sourced from `gradle.properties`.

Database schema SQL files live in `common/src/main/resources/database/` — applied at plugin startup, one file per engine (MySQL, MariaDB, PostgreSQL; MongoDB manages its own schema).

## Adding a New Syncable Data Type

1. Add a new sub-interface to `Data` in `common`
2. Implement a `Serializer<YourData>` and register it in `SerializerRegistry`
3. Implement the data type in both `bukkit` and `fabric` modules
4. Add the corresponding `Identifier` key

## License Headers

All source files must include the Apache 2.0 license header. The `org.cadixdev.licenser` Gradle plugin enforces this — run `./gradlew licenseFormat` to auto-apply headers.