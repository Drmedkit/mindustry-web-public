# Mindustry browser-port compatibility audit

> Implementation update — 2026-07-12: this document began as the pre-port
> audit, so many later sections intentionally preserve the original blockers
> and evidence. The Ground Zero P0 slice is now implemented. TeaVM produces a
> 23.2 MB optimized JavaScript bundle; Chromium renders and accepts input in the
> campaign; save creation and cross-process restoration pass; the complete
> asset set and both atlases are packaged; FreeType runs through the adapted
> browser bridge; scoped reflection covers Ground Zero serialization; and
> offline Arc, TeaVM probe, Mindustry desktop and server compilation gates pass.
> Remaining gaps are silent audio, multiplayer/mods, Firefox/Safari validation,
> IndexedDB-scale persistence, browser file import/export and full-campaign
> endurance/parity testing. See `README.md` and `PORTING_PLAN.md` for the live
> status.

Audit date: 2026-07-11. Scope: the pinned Arc and Mindustry source snapshots,
TeaVM JavaScript as the first compiler target, and single-player Serpulo. This is
a source audit, not a claim that the game has already run under TeaVM.

## Decision

The port is feasible without rewriting the game simulation, but the current
sources are not a drop-in browser build. Keep Mindustry and Arc's Java gameplay,
content, UI, and rendering layers. Add an Arc web backend and replace reachable
JVM/native boundaries with browser implementations.

Use TeaVM 0.15.0 JavaScript first. Reuse selected source and techniques from
`gdx-teavm`, adapted behind Arc's interfaces. Do not replace Arc with libGDX and
do not treat the libGDX backend as binary-compatible with Arc.

Priority meanings in this document:

- **P0**: must be resolved to compile and directly launch a playable Ground Zero
  browser slice.
- **P1**: may follow that slice, but must be resolved for complete persistent
  single-player Serpulo.
- Multiplayer, JVM/Rhino mods, the editor, Steam, Discord, and arbitrary host
  filesystem access are deferred features, not P0/P1 campaign blockers.

## Pinned source snapshots

The extracted GitHub archives do not contain `.git` metadata. Their archive
comments identify the commits; the ZIP hashes make the local inputs
reproducible.

| Component | Commit from ZIP comment | Local ZIP SHA-256 |
| --- | --- | --- |
| Mindustry | `439799ce8ff1480d1d169c5ba7ff3def422999de` | `2b018924efced0ad6cdbb505cf77cb1d3151c522310c34f32dffbd34f486d63e` for `Mindustry-master.zip` |
| Arc | `6a97615f6fd9f8e9af573d810fe8591ad483224c` | `e08af3d3cb81ece620de6b9e42a8979ef0ab81dc31aab5d41e3682bd23227ca2` for `Arc-master.zip` |

Verification commands:

```bash
unzip -z ~/Downloads/Mindustry-master.zip
unzip -z ~/Downloads/Arc-master.zip
sha256sum ~/Downloads/Mindustry-master.zip \
  ~/Downloads/Arc-master.zip
```

Mindustry pins `archash=6a97615f6f`, the exact prefix of the Arc archive commit,
so the two snapshots are paired intentionally
([gradle.properties:29](Mindustry/gradle.properties:29)).

## Proven facts

### Arc baseline

- Arc explicitly removed its GWT module and its browser workarounds
  ([Arc README:21](Arc/README.md:21)).
  The pinned snapshot included Android, headless, SDL, SDL3, and RoboVM
  backends, but no web backend. This port now registers a `backend-teavm`
  module; at present it contains the runtime bootstrap and VFS foundation, not
  yet a complete application/graphics/input backend
  ([settings.gradle:16](Arc/settings.gradle:16)).
- The core API still contains `ApplicationType.web`
  ([Application.java:139](Arc/arc-core/src/arc/Application.java:139)),
  and platform services are already centralized in `Core`
  ([Core.java:18](Arc/arc-core/src/arc/Core.java:18)).
  These are useful seams for a new backend.
- All 382 Java files in `Arc/arc-core/src` compile successfully with the local
  JDK using `javac --release 8` plus Arc's bundled `unsafe.jar`; the result is
  644 class files. The successful audit command was equivalent to:

  ```bash
  find Arc/arc-core/src -type f -name '*.java' -print0 |
    xargs -0 javac --release 8 \
      -cp Arc/arc-core/unsafe/unsafe.jar \
      -d /tmp/arc-core-compile-audit
  ```

  This proves ordinary Java source integrity only. Native declarations compile
  without implementations, and `unsafe.jar` itself uses `sun.misc.Unsafe`; it
  is not a browser runtime solution.
- The checked-in probe includes the complete Arc core source set and TeaVM
  0.15.0
  ([browser-probe/build.gradle:1](browser-probe/build.gradle:1)).
  The TeaVM configuration compiles for Java 17 because TeaVM 0.15's runtime
  libraries require Java 11 or 17; the separate Arc source gate retains Java 8
  bytecode verification. The probe now produces JavaScript successfully, and
  headless Chromium executes its Arc collection, packaged Ground Zero asset,
  writable VFS, and mutation-journal checks. Firefox execution remains pending.
- Arc now has opt-in pure-Java buffer and pixmap paths plus dependency-free
  semantic checks
  ([Buffers.java:15](Arc/arc-core/src/arc/util/Buffers.java:15),
  [PixmapFallbackCheck.java:1](Arc/arc-core/test/PixmapFallbackCheck.java:1)).
  Arc also has explicit web platform detection, silent audio that guards every
  reachable SoLoud call, and serial threadless executor factories. A single
  `TeaRuntime.configure()` call selects these modes before `Core` initializes
  ([TeaRuntime.java:1](Arc/backends/backend-teavm/src/arc/backend/teavm/TeaRuntime.java:1)).
  Desktop behavior remains native and worker-backed by default. The fallback
  suite passes buffer copy/lifecycle, PNG decode/pixmap copy, eager filter,
  platform identity, reentrant executor, and combined `Core` initialization
  checks on the JVM.
  TeaVM must still prove that the unused JNI branches are removed or replace
  them with a compile-time substitution.
- The synchronous browser storage core is implemented independently of TeaVM
  APIs. It supports preloads, deterministic listing, writable files,
  recursive moves/deletes, defensive byte ownership, and an ordered mutation
  journal; its 71-case JVM test passes. `TeaFiles`/`TeaFi` now expose that store
  through Arc's normal synchronous file API while separating packaged read-only
  assets from writable local data; 93 additional contract checks pass
  ([TeaFileStore.java:1](Arc/backends/backend-teavm/src/arc/backend/teavm/TeaFileStore.java:1),
  [TeaFiles.java:1](Arc/backends/backend-teavm/src/arc/backend/teavm/TeaFiles.java:1),
  [TeaFilesTest.java:1](Arc/backends/backend-teavm/test/arc/backend/teavm/TeaFilesTest.java:1)).
  A 23-check integration test also proves Arc `Settings` primitive/binary
  round-trips, rolling backups, stable backups, and corrupt-primary recovery on
  the virtual local filesystem
  ([TeaSettingsTest.java:1](Arc/backends/backend-teavm/test/arc/backend/teavm/TeaSettingsTest.java:1)).
- Arc targets Java 8
  ([Arc build.gradle:84](Arc/build.gradle:84));
  Mindustry core compiles with Java 17
  ([Mindustry build.gradle:243](Mindustry/build.gradle:243)).
  The Arc-only Java 8 probe does not yet validate Mindustry's Java 17 bytecode.

### Mindustry baseline

- The source archive does not contain `mindustry.gen` entity, group, remote-call,
  or asset-region sources. Mindustry creates them with its annotation processor
  and includes the generated directory in core compilation
  ([core/build.gradle:1](Mindustry/core/build.gradle:1),
  [build.gradle:374](Mindustry/build.gradle:374)).
  The browser build must run this host-side generation before TeaVM compilation;
  it cannot compile only the checked-in core Java tree.
- The source ZIP omitted generated `core/assets/version.properties`, the normal
  `sprites/sprites.aatls`, and the fallback atlas. This port now supplies
  deterministic custom-build version metadata for the pinned commit. Startup reads the version
  file
  ([Version.java:30](Mindustry/core/src/mindustry/core/Version.java:30))
  and queues the atlas
  ([ClientLauncher.java:163](Mindustry/core/src/mindustry/ClientLauncher.java:163)).
  The host-side build already knows how to write version metadata
  ([build.gradle:123](Mindustry/build.gradle:123))
  and generate both atlases
  ([tools/build.gradle:176](Mindustry/tools/build.gradle:176)).
  Generate these once on the JVM and package the outputs; do not port the asset
  packer to the browser.
- Ground Zero is an upstream campaign preset and is always unlocked
  ([SectorPresets.java:22](Mindustry/core/src/mindustry/content/SectorPresets.java:22)).
  After normal content initialization, the narrow direct-launch seam is:

  ```java
  control.playSector(SectorPresets.groundZero.sector);
  ```

  The public entry point is
  [Control.java:427](Mindustry/core/src/mindustry/core/Control.java:427).
- The Ground Zero map is already present at
  [groundZero.msav](Mindustry/core/assets/maps/serpulo/groundZero.msav).
  The campaign preset constructor synchronously opens map metadata
  ([SectorPreset.java:70](Mindustry/core/src/mindustry/type/SectorPreset.java:70),
  [FileMapGenerator.java:22](Mindustry/core/src/mindustry/maps/generators/FileMapGenerator.java:22)).
  Required maps must therefore be in the in-memory VFS before content creation.
- A deterministic manifest now records all 664 present asset files, totaling
  61,032,719 bytes, including the generated version metadata and a hash for Ground Zero
  ([assets-manifest.json](browser-port/assets-manifest.json),
  [generate-asset-manifest.mjs](scripts/generate-asset-manifest.mjs)).
  Its `requiredGeneratedAssets` section confirms that version metadata is
  present and both sprite atlases are still missing. Two consecutive
  regenerations produced SHA-256
  `3ae1c877faafa19fa7469d2e1caff87ef33fe055f166d90531a8fc246f1aac87`.

## Chosen `gdx-teavm` reuse strategy

The audited `gdx-teavm` master is Apache-2.0, targets libGDX 1.14.2 and TeaVM
0.15.0, and contains the browser implementations that Arc no longer carries.
The latest audited release, `v1.5.6` dated 2026-05-11, still targets libGDX
1.14.0 and TeaVM 0.14.0. Do not force its binaries onto TeaVM 0.15. For this
port, obtain current source and pin its exact commit instead of floating on
`master` or `-SNAPSHOT`.

Upstream references:

- [gdx-teavm repository](https://github.com/xpenatan/gdx-teavm)
- [gdx-teavm v1.5.6](https://github.com/xpenatan/gdx-teavm/releases/tag/1.5.6)

Apache-2.0 code may be combined into this GPL-3.0 project. Preserve its headers
and notices; distribute the combined browser fork under GPL-3.0 with
corresponding source.

Reuse is source-level and selective:

| `gdx-teavm` area | Reuse | Arc-side integration |
| --- | --- | --- |
| `backend-web` | Browser lifecycle, `requestAnimationFrame`, DOM input, canvas/fullscreen, WebGL bindings, WebAudio primitives | Implement Arc-specific `WebApplication`, `WebGraphics`, `WebInput`, `WebAudio`, and GL adapters. Arc's GL and graphics types remain authoritative. |
| `backend-shared` | Asset preloader/VFS patterns and TeaVM reflection metadata support | Build an Arc `WebFiles`/`WebFi` synchronous facade over preloaded bytes; adapt reflection support only for registered Arc/Mindustry classes. |
| `gdx-freetype-web` | Browser-compatible FreeType/Wasm bridge and glyph rasterization | Implement Arc's `FreeType` surface or a narrow adapter used by `FreeTypeFontGenerator`; do not translate 68 JNI methods independently. |
| TeaVM 0.15.0 | Java-to-JavaScript compiler and JSO bindings | Keep the existing probe version, compile an explicit browser source set, and use TeaVM substitutions for unreachable JVM/native implementations. |

Three constraints prevent wholesale backend reuse:

1. Arc deliberately diverged from libGDX and removed GWT support
   ([Arc README:21](Arc/README.md:21)).
2. Arc APIs use different packages and altered rendering/audio semantics, so a
   libGDX backend cannot satisfy Arc interfaces directly.
3. Pulling all of libGDX beside Arc would duplicate core types and enlarge the
   TeaVM reachability graph. Only the proven browser primitives should cross
   the adapter boundary.

The filesystem design must respect the browser's asynchronous APIs while
preserving Mindustry's synchronous reads:

1. Generate an asset manifest during the host build.
2. Fetch and preload the boot set before `ClientLauncher.setup()`; initially
   include all core metadata plus Ground Zero.
3. Serve synchronous `Fi` reads/listing from an in-memory tree. Arc already
   demonstrates backend-specific `Fi` subclasses
   ([AndroidFi.java:20](Arc/backends/backend-android/src/arc/backend/android/AndroidFi.java:20)).
4. Mirror writable files in memory, journal mutations, and flush them to
   IndexedDB asynchronously at explicit checkpoints. `Settings.forceSave()` is
   synchronous today
   ([Settings.java:90](Arc/arc-core/src/arc/Settings.java:90));
   browser shutdown cannot promise that a newly started transaction completes.

## P0 blockers: direct playable Ground Zero

| Blocker | Evidence | Required treatment |
| --- | --- | --- |
| No complete Arc browser backend | The new `backend-teavm` module currently provides runtime/VFS foundations only ([settings.gradle:16](Arc/settings.gradle:16)); the existing `web` enum is only a seam. | Import and adapt the pinned `gdx-teavm` primitives. Establish lifecycle, frame timing, resize, visibility, clipboard, fullscreen, input, GL20, and optional GL30. |
| TeaVM/Mindustry compile is unproven | The probe compiles Arc plus the new TeaVM runtime/VFS as Java 8 ([browser-probe/build.gradle:13](browser-probe/build.gradle:13)); Mindustry uses Java 17 and depends on host-generated `mindustry.gen` sources. | Warm the Gradle/TeaVM cache, make the Arc probe pass, run Mindustry's annotation processor on the host, then add generated classes and core incrementally. Record every TeaVM diagnostic; do not infer runtime support from `javac`. |
| Native buffers and pixmaps | Arc's default path allocates/frees native buffers and exposes JNI copy/address functions ([Buffers.java:234](Arc/arc-core/src/arc/util/Buffers.java:234)). | JVM semantics are implemented and tested: browser mode uses heap buffers, pure-Java copies, Java-managed unsafe-buffer lifecycle, heap pixmaps, and the pure PNG decoder. The remaining gate is proving TeaVM eliminates or substitutes unreachable native declarations, then mapping buffer uploads efficiently to WebGL typed arrays. |
| Browser platform identity | The pinned source treated unknown operating systems as iOS ([OS.java:39](Arc/arc-core/src/arc/util/OS.java:39)). | Resolved in the Arc layer: `TeaRuntime.configure()` explicitly selects web and clears desktop/mobile/native-ABI flags. Process execution and environment APIs must still remain outside the reachable browser graph. |
| Synchronous filesystem versus Fetch/IndexedDB | `Fi` stores `java.io.File` and synchronously opens streams ([Fi.java:28](Arc/arc-core/src/arc/files/Fi.java:28)); listing/writes are also synchronous ([Fi.java:409](Arc/arc-core/src/arc/files/Fi.java:409), [Fi.java:620](Arc/arc-core/src/arc/files/Fi.java:620)). | The in-memory store, async-safe change journal, and `TeaFiles`/`TeaFi` Arc adapter are complete and JVM-tested. Implement Fetch preloading and IndexedDB hydrate/flush next. The boot promise must resolve before any synchronous game read, and browser setup must point `Settings.dataDirectory` at a local `TeaFi`. |
| Missing generated startup assets | Deterministic version metadata now exists, but the archive still lacks both `.aatls` files required by startup ([ClientLauncher.java:163](Mindustry/core/src/mindustry/ClientLauncher.java:163)). | Run upstream host generation and capture deterministic outputs, or bootstrap both atlases from an official archive with recorded hashes/provenance. |
| Runtime FreeType is JNI-only | `Fonts.loadFonts()` queues runtime WOFF/TTF generation ([Fonts.java:64](Mindustry/core/src/mindustry/ui/Fonts.java:64)); Arc's FreeType binding declares 68 native methods beginning at [FreeType.java:96](Arc/extensions/freetype/src/arc/freetype/FreeType.java:96). | Port the narrow Arc font API using `gdx-freetype-web`. Validate incremental glyphs, outline/border generation, icon fonts, and Japanese fallback. Prebaked fonts are a temporary boot fallback only. |
| Reachable SoLoud JNI | Arc audio initializes SoLoud by default ([Audio.java:25](Arc/arc-core/src/arc/audio/Audio.java:25)), and Mindustry constructs a `BiquadFilter` immediately ([SoundControl.java:45](Mindustry/core/src/mindustry/audio/SoundControl.java:45)). | The opt-in silent fallback now guards audio, sources, buses, filters, sounds, and music, including eager `BiquadFilter` construction. Prove these branches under TeaVM, then map them to WebAudio after user activation. |
| Runtime reflection | Arc JSON discovers declared fields and constructors at runtime ([Json.java:157](Arc/arc-core/src/arc/util/serialization/Json.java:157), [Json.java:1258](Arc/arc-core/src/arc/util/serialization/Json.java:1258)); Scene discovers style fields ([Scene.java:87](Arc/arc-core/src/arc/scene/Scene.java:87)). Mindustry also discovers every block's nested `Building` class and constructor ([Block.java:1246](Mindustry/core/src/mindustry/world/Block.java:1246)). | The building-constructor slice is implemented: a deterministic javac-AST generator emits 154 ordered built-in factories, and strict browser installation avoids reflective construction ([BuiltinBlockBuildFactories.java:1](Mindustry/core/src/mindustry/world/BuiltinBlockBuildFactories.java:1)). Its structural completeness check passes; the full reflection-parity JUnit test awaits Gradle dependencies. JSON fields/constructors and Scene style registration still need TeaVM metadata or generated registries. |
| JVM threads/executors on startup and during play | `Core.executor` is created statically ([Core.java:32](Arc/arc-core/src/arc/Core.java:32)); AssetManager creates an executor ([AssetManager.java:53](Arc/arc-core/src/arc/assets/AssetManager.java:53)); the original Timer starts a daemon thread ([Timer.java:315](Arc/arc-core/src/arc/util/Timer.java:315)). Mindustry's frame processes use an executor barrier ([AsyncCore.java:40](Mindustry/core/src/mindustry/async/AsyncCore.java:40)) and enemy/control pathfinding still creates dedicated threads ([Pathfinder.java:282](Mindustry/core/src/mindustry/ai/Pathfinder.java:282), [ControlPathfinder.java:388](Mindustry/core/src/mindustry/ai/ControlPathfinder.java:388)). | Arc executor factories now have a serial, reentrancy-safe browser mode; `Core.executor` initialization is verified when configured first. Timer also has a no-thread strategy driven from `ApplicationListener.update()`, with next-frame and main-thread tests. `AsyncCore` now obtains its pool from the same Arc factory, preserving desktop parallelism while executing serially in browser mode. The two pathfinder loops remain unresolved and need bounded main-loop stepping; Web Workers are a later optimization. |
| Default single-player startup constructs native networking | The original `Platform.getNet()` default created `ArcNetProvider`; client setup invokes it unconditionally ([ClientLauncher.java:152](Mindustry/core/src/mindustry/ClientLauncher.java:152)). `ArcNetProvider` eagerly creates raw TCP/UDP clients, servers, threads, and LZ4 codecs ([ArcNetProvider.java:44](Mindustry/core/src/mindustry/net/ArcNetProvider.java:44)). | Web applications now receive a socket-free `OfflineNetProvider` from the platform boundary ([OfflineNetProvider.java:1](Mindustry/core/src/mindustry/net/OfflineNetProvider.java:1)); a full JUnit contract test is checked in but awaits the dependency cache. TeaVM must still exclude `ArcNetProvider`/ArcNet from its reachable output. Multiplayer remains deferred. |
| Mods and Rhino are on the normal client path | Core has an unconditional Rhino dependency ([build.gradle:384](Mindustry/build.gradle:384)); setup creates `Mods`, calls `loadScripts`, and loads the mod asset ([ClientLauncher.java:154](Mindustry/core/src/mindustry/ClientLauncher.java:154), [ClientLauncher.java:170](Mindustry/core/src/mindustry/ClientLauncher.java:170)). `Vars.init()` also scans mod and map directories ([Vars.java:349](Mindustry/core/src/mindustry/Vars.java:349)). | Add a browser feature gate and no-op mod/scripting implementation. Preserve vanilla content initialization while excluding dynamic class loading, JARs, and Rhino from TeaVM reachability. |
| Client loop sleeps to cap FPS | The normal update loop can call `Threads.sleep` ([ClientLauncher.java:263](Mindustry/core/src/mindustry/ClientLauncher.java:263)). | The loop now disables its blocking FPS cap when `Core.app.isWeb()`; the browser backend must own cadence through `requestAnimationFrame` and derive delta from the animation timestamp. TeaVM still needs to prove the unused sleep branch is harmless or eliminated. |
| Campaign map metadata is read eagerly | Every `SectorPreset.initialize()` creates a `FileMapGenerator` ([SectorPreset.java:70](Mindustry/core/src/mindustry/type/SectorPreset.java:70)), which synchronously resolves and parses its map metadata ([FileMapGenerator.java:22](Mindustry/core/src/mindustry/maps/generators/FileMapGenerator.java:22)). | For the first slice, preload all vanilla campaign map metadata or introduce an async content-loading barrier before presets initialize. Ground Zero's full bytes must be present before direct launch. |

## P1 blockers: complete persistent Serpulo

| Blocker | Evidence | Exit treatment |
| --- | --- | --- |
| Durable campaign/settings saves | Settings writes synchronously, performs filesystem backups on an executor, and assumes atomic-looking file operations ([Settings.java:209](Arc/arc-core/src/arc/Settings.java:209)). Sector info and planet rules/stats are JSON blobs in Settings ([Sector.java:101](Mindustry/core/src/mindustry/type/Sector.java:101), [Planet.java:251](Mindustry/core/src/mindustry/type/Planet.java:251)). | Arc Settings round-trip and backup recovery already pass against the local in-memory VFS. Complete the IndexedDB snapshot/flush protocol around its journal. Flush on campaign transitions, autosaves, visibility loss, and explicit export; recover from an interrupted browser transaction in tests. |
| Reflection-complete campaign serialization | Save metadata serializes rules, stats, locales, objectives, and control groups through `JsonIO` ([SaveVersion.java:113](Mindustry/core/src/mindustry/io/SaveVersion.java:113)); `JsonIO` delegates unknown fields/classes to Arc reflection ([JsonIO.java:22](Mindustry/core/src/mindustry/io/JsonIO.java:22)). | Generate/register metadata for every Serpulo save/campaign type. Round-trip representative saves from early, mid, and completed campaigns and compare semantic state. |
| Lazy sector/map asset loading | Full Serpulo creates many presets and `Maps.load()` synchronously walks default, custom, workshop, and mod locations ([Maps.java:131](Mindustry/core/src/mindustry/maps/Maps.java:131)). | Prepackage all Serpulo maps and metadata. Add a loading transition that fetches a sector's heavy bytes before `playSector`; browser builds skip custom/workshop/mod scans. |
| Long-running pathfinding and simulation budget | Current pathfinders assume continuously running low-priority threads and sleeps ([Pathfinder.java:321](Mindustry/core/src/mindustry/ai/Pathfinder.java:321)). | Demonstrate bounded per-frame work across the largest Serpulo sectors. Move isolated numeric workloads to Workers only if incremental main-thread execution misses the agreed frame budget. |
| Audio parity and browser activation | WebAudio cannot start reliably before a user gesture, while upstream initializes audio during application setup ([Audio.java:41](Arc/arc-core/src/arc/audio/Audio.java:41)). | Resume/create the audio context from the first user activation; validate music streaming, buses, pause/resume, fades, and the low-pass pause effect. Gameplay remains functional when audio is denied. |
| Save/schematic import and export | Upstream APIs assume `Fi` and a platform file chooser ([Platform.java:101](Mindustry/core/src/mindustry/core/Platform.java:101)). | Map import to browser file input and export to Blob download. Keep direct arbitrary filesystem access disabled. |

## Next milestone: browser shell and Ground Zero first frame

This milestone is complete only when all of the following are reproducible from
the pinned snapshots:

1. `./gradlew browserDist` completes with TeaVM 0.15.0 and emits a static site.
   A second build succeeds offline from the warmed dependency cache.
2. The existing Arc probe runs in both Chromium and Firefox and displays its
   `Seq`/`Mathf` result. This closes the compiler gate before backend work is
   counted as passing.
3. The browser backend initializes `Core.app`, `Core.files`, `Core.graphics`,
   `Core.input`, `Core.audio`, and `Core.settings`; one `requestAnimationFrame`
   loop completes Arc setup/init/update/render without a native call,
   unsupported reflection error, blocking sleep, or background JVM thread.
4. WebGL renders a clear, the generated Mindustry atlas, and text generated by
   the web FreeType adapter. Resize, device-pixel ratio, keyboard, pointer,
   wheel, clipboard, focus/visibility, and fullscreen smoke tests pass.
5. The checked-in preload manifest is regenerated reproducibly, then supplies
   generated version metadata and atlases plus locales, shaders, sprites,
   fonts, content metadata, and Ground Zero before Mindustry setup starts. No
   synchronous game read waits on Fetch.
6. The browser platform uses no-op networking, mods, scripting, editor, Steam,
   and Discord services. Their JVM/native libraries are absent from the
   reachable TeaVM output.
7. Vanilla content initialization completes, then
   `control.playSector(SectorPresets.groundZero.sector)` reaches the first
   rendered campaign frame with the player core, terrain, UI, and wave state
   present.
8. A reload can restore at least one settings value and one Ground Zero sector
   state record from IndexedDB. Full save compatibility and long-session
   guarantees remain P1.

Passing this milestone proves the architecture. It does not yet claim conveyor,
combat, wave, win-state, or full Serpulo parity; those become the following
vertical-slice acceptance tests.
