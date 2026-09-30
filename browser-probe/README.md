# Arc TeaVM probe

This is intentionally smaller than a browser backend. It compiles the complete
Arc core source set plus the browser in-memory filesystem, then makes TeaVM
execute real `Seq`, `Mathf`, packaged-asset, and writable-save paths in a
browser.

Build from this directory using the probe's Gradle 9.6.1 wrapper. The probe has
its own wrapper because the installed Java 26 runtime requires Gradle 9.4 or
newer; upstream currently pins 9.3.1.

```bash
./gradlew --no-daemon browserDist
```

Serve the generated static directory:

```bash
python3 -m http.server 8080 -d build/browser-dist
```

Then open <http://localhost:8080>. A successful run replaces the loading text
with a message naming Ground Zero.

The generated distribution can also be checked automatically in installed
Chromium without starting an HTTP server:

```bash
../scripts/check-browser-probe.sh
```

The first build downloads Gradle 9.6.1 and TeaVM 0.15.0 from the Gradle Plugin
Portal and Maven Central. Once those artifacts are cached, subsequent probe
builds can run offline.
