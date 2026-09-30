# Mindustry Serpulo Browser Port

## Objective

Run the current GPL-3.0 Mindustry code and Serpulo campaign locally in a modern
browser. Preserve the existing Java simulation and content code wherever
possible; replace platform integrations at the Arc boundary.

This is an unofficial fork. Distributed builds must include the corresponding
modified source and retain upstream copyright and license notices.

## Source layout

The upstream projects are expected side-by-side:

```text
mindustry-web-public/
  Arc/
  Mindustry/
  PORTING_PLAN.md
```

Upstreams:

- https://github.com/Anuken/Arc
- https://github.com/Anuken/Mindustry

The initial source snapshot is pinned by GitHub archive comments and ZIP hashes:

- Mindustry commit `439799ce8ff1480d1d169c5ba7ff3def422999de`
  - ZIP SHA-256: `2b018924efced0ad6cdbb505cf77cb1d3151c522310c34f32dffbd34f486d63e`
- Arc commit `6a97615f6fd9f8e9af573d810fe8591ad483224c`
  - ZIP SHA-256: `e08af3d3cb81ece620de6b9e42a8979ef0ab81dc31aab5d41e3682bd23227ca2`

The Arc commit matches Mindustry's `archash` exactly.

## Current implementation state

- TeaVM 0.15 compiles the complete reachable Mindustry client, Arc backend and
  Ground Zero content into a production JavaScript bundle. Aggressive
  optimization reduces the main bundle to approximately 23.2 MB, below
  Cloudflare Pages' 25 MiB per-file limit.
- Chromium renders the actual Ground Zero map, player core, minimap, HUD and
  tutorial objectives. A real-time automated smoke test clicks the canvas,
  sends movement input, opens the in-game menu with Escape and observes no page
  or console errors.
- Ground Zero launches automatically after `ClientLoadEvent`. Map rules,
  objectives, markers, waves, weather and save metadata have narrowly scoped
  TeaVM reflection policies; the 154 built-in block factories remain generated
  without runtime constructor discovery.
- Arc has a Canvas/WebGL lifecycle, DOM input, typed-array GL adapter, Fetch
  asset preloader, browser VFS, browser FreeType bridge, silent SoLoud surface,
  threadless executors and requestAnimationFrame-owned cadence.
- All 681 Mindustry assets plus Arc FX shaders preload before Java startup. Both
  sprite atlases are locally generated from pinned sources and recorded in the
  provenance manifest.
- Writable VFS mutations flush to origin-scoped browser storage. A clean launch
  creates a campaign save and a second browser process restores it successfully.
- Networking is socket-free and offline. JVM mods, Rhino scripting and native
  platform services are outside the browser reachability graph. Audio is
  intentionally silent in this release.
- The offline gates compile Arc with Java 8 compatibility, exercise SHA-256,
  buffers, pixmaps, timers, settings and VFS behavior, verify generated block
  factories and manifests, run the TeaVM browser probe, and compile Mindustry
  desktop and server sources with the browser changes present.

## Architecture

- TeaVM JavaScript is the initial compiler target. WebAssembly GC is the second
  candidate if JavaScript output size or runtime performance is unacceptable.
- Add an Arc browser backend instead of rewriting Mindustry in TypeScript.
- Browser platform services:
  - Canvas and WebGL 2 graphics
  - WebAudio audio and music
  - DOM keyboard, pointer, wheel, touch, clipboard, and fullscreen integration
  - IndexedDB-backed settings, saves, and campaign state
  - Fetch-backed packaged asset loading
  - Browser download/upload flows for save and schematic import/export
- Unsupported JVM/native calls must be isolated behind Arc interfaces or
  replaced at compile time. Do not scatter browser conditionals throughout the
  gameplay simulation.

## Milestone 0: compatibility audit

Exit criteria:

- Upstream desktop build runs unchanged.
- TeaVM compiles a minimal program against `arc-core`.
- Every compile blocker in `arc-core` and Mindustry core is classified as:
  platform implementation, native dependency, reflection/code generation,
  threads, filesystem, networking, or optional feature.
- Compiler choice is confirmed with a small renderer benchmark.

## Milestone 1: browser shell

Exit criteria:

- A reproducible Gradle task emits a static web distribution.
- The app opens a responsive canvas and completes the Arc lifecycle.
- WebGL 2 renders the Mindustry texture atlas and text.
- Input, resize, fullscreen, pause/resume, and error reporting work.
- Audio starts correctly after browser user activation.

## Milestone 2: Ground Zero vertical slice

Exit criteria:

- The Serpulo menu and Ground Zero load from upstream assets.
- Camera movement, mining, building, conveyors, turrets, waves, enemy AI,
  pause, win/loss, and campaign progression work.
- Reloading the page restores settings and campaign/save state from IndexedDB.
- A 30-minute session has no game-breaking error or unbounded memory growth.
- Chrome and Firefox desktop pass; Safari failures are documented.

Initially disabled:

- Multiplayer and public server browser
- Java/JavaScript mods and Rhino scripting
- Map editor
- Steam, Discord, and mobile platform services
- Direct arbitrary filesystem access

## Milestone 3: complete single-player Serpulo

Exit criteria:

- All Serpulo sectors, tech tree progression, sector saves, launch/landing pads,
  resources, objectives, and difficulty settings work.
- Save import/export is compatible where browser constraints permit.
- Long campaign sessions meet an agreed memory and frame-time budget.
- Automated smoke tests cover loading every Serpulo sector and content type.

## Later milestones

1. Mobile browser controls and performance.
2. WebSocket multiplayer transport and a server-side protocol bridge.
3. Map editor and browser file workflows.
4. A browser-safe mod format; arbitrary JVM mods are out of scope unless a
   compatible sandboxed runtime is proven.
5. Erekir and remaining parity work.

## First technical decisions

1. Port rendering and input before campaign UI.
2. Use packaged assets first; implement user files only after the game boots.
3. Stub audio only long enough to reach first render, then implement WebAudio.
4. Disable optional services at the source boundary instead of emulating them.
5. Keep upstream changes reviewable in small commits to make future rebases
   possible.

## Immediate next actions

1. Validate Firefox and Safari and document any WebGL/FreeType differences.
2. Add WebAudio behind a user-activation gate; music and sounds are already
   fetched on first read, so they cost nothing until then.
3. Run long-session wave, enemy pathfinding, build/mining and sector-completion
   tests, then expand automated coverage across every Serpulo preset.
4. Add browser-native save/schematic import and export.
5. Co-op over a Cloudflare Durable Object relay (browser-hosted server), then
   multiplayer on custom maps.
