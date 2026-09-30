# Performance on low-end devices

What makes the browser build heavy, what has been done about it, and what was
tried and rejected. Measurements use `scripts/bench` style runs: headless
Chrome over the DevTools protocol on a desktop with CPU throttling as a
stand-in for a slow laptop, loading Ground Zero from a fresh profile.

## Results

Before: the live site on 2026-09-30. After: this repository, served locally.
Same machine, 4x CPU throttle, warm cache.

| | Before | After |
|---|---|---|
| Time to ready | 39.9 s | 22.8 s |
| Longest main-thread freeze during boot | 6.4 s | 4.2 s |
| Renderer process memory | 841 MB | 420 MB |
| JavaScript heap | 194 MB | 135 MB |
| Startup download (assets) | 73.5 MB | 18.7 MB |
| Startup download (game script, Brotli) | 3.9 MB | 2.9 MB |

## What changed

- **Download.** Only files the game reads at startup are fetched before Java
  starts (`asset-preloader.js`). Music and sounds (audio is silent), the atlas
  this GPU does not use, other languages and the Japanese font are fetched on
  first read. Erekir's sprites sit on atlas pages that are never downloaded
  (`scripts/split-erekir-sprites.mjs`), and atlas pages are sized to their
  contents, so the Serpulo pages hold 19.7 instead of 32 megapixels.
- **Memory.** Preloaded assets and hydrated saves are no longer kept as extra
  copies in JavaScript, and the file store takes ownership of asset bytes.
- **Startup CPU.** Large PNGs are decoded by the browser's native zlib while
  other files download (`png-decoder.js`); Java's decoder spent seconds on the
  atlas under TeaVM, where 64-bit checksum maths becomes BigInt operations.
  Java start-to-ready fell from about 9 s to 5 s unthrottled.
- **Frames.** A first-run preset gives weak devices (software GPU, 4 GB RAM or
  4 threads) a 1x pixel ratio, a 30 FPS cap and no bloom, animated water,
  shields, atmosphere or weather; other devices get a 60 FPS cap. `glGetError`
  is no longer polled (a GPU-process stall in browsers).
- **Hitches.** Pathfinding runs on the main thread in browsers. Its slices are
  now 2-3 ms instead of 8-12 ms, and the flow-field pathfinder yields between
  fields.

## Rejected for now

### WebAssembly (TeaVM Wasm GC)

`./gradlew -Pproduction=true generateWasmGC` in `browser-game/` compiles the
whole game through analysis and code generation, then fails in TeaVM 0.15:

    IllegalArgumentException: Unsupported generic type: null
      at org.teavm.backend.wasm.intrinsics.reflection.ReflectionMetadataGenerator.generateGenericType

Reflection metadata for fields is exactly what the game's JSON saves rely on
(`MindustryReflectionSupplier`), so this cannot be worked around by dropping
reflection. Even once TeaVM fixes it:

- TeaVM 0.15's Wasm output requires Chrome 137+ (exnref exceptions), so older
  school Chromebooks would still need this JavaScript build.
- Heap `ByteBuffer`s passed to WebGL (the port's buffer strategy) are not
  supported across the Wasm/JS boundary and would need a direct-buffer path.
- Expected gain is 1.3-1.5x on simulation-heavy frames and none on
  GPU-bound frames, which is where weak devices struggle.

Revisit when TeaVM ships a fix and Chrome 137+ is widespread on school devices.

### Turning off TeaVM strict mode

`-PteavmStrict=false` removes null and bounds checks, but the production
bundle grows from 23.3 MB to 32.0 MB (over Cloudflare Pages' 25 MiB file
limit) and code relying on those exceptions would silently misbehave.

## Next candidates

1. WebGL2 vertex array objects and `bufferSubData` in `TeaGL20`: `bufferData`
   is the largest native call per frame.
2. Pathfinding in a Web Worker (host only in co-op), removing its remaining
   main-thread cost on big maps.
3. An int-based `Rand` to avoid BigInt in effects and AI. It changes random
   sequences, so check world generation and co-op consistency.
4. A real half-resolution atlas for GPUs with little memory (the existing
   "fallback" atlas has the same resolution split into smaller pages).
