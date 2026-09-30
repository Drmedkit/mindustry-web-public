# Pinned browser-port dependencies

This file records inputs that are not represented by Git metadata in the
extracted upstream trees. Build outputs must be reproducible from these pins.

| Input | Version or commit | SHA-256 |
| --- | --- | --- |
| Mindustry source archive | `439799ce8ff1480d1d169c5ba7ff3def422999de` | `2b018924efced0ad6cdbb505cf77cb1d3151c522310c34f32dffbd34f486d63e` |
| Arc source archive | `6a97615f6fd9f8e9af573d810fe8591ad483224c` | `e08af3d3cb81ece620de6b9e42a8979ef0ab81dc31aab5d41e3682bd23227ca2` |
| Gradle binary distribution | `9.6.1` | `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14` |
| Eclipse Temurin JDK | `17.0.19+10`, Linux x64 | `d8afc263758141a66e0e3aafc321e783f7016696f4eaea067d340a269037d331` |
| TeaVM Gradle plugin and runtime | `0.15.0` | resolved by Gradle from the Plugin Portal and Maven Central |
| gdx-teavm source archive | `c864fc2523e2fe414ffc8ffefba7413ca103cd31` | `3d0554cbcbe505383660c50e74373e58fcbd046d845dcb3d6990646f69729e62` |
| Wrangler CLI | `4.110.0` | pinned through the local npm project before deployment |

Downloaded archives and the optional JDK are local caches excluded from Git.
The extracted Apache-2.0 gdx-teavm source and license are committed under
`vendor/src/`. Only selected browser
primitives are adapted into Arc. Mindustry and this combined fork remain
distributed under GPL-3.0 with corresponding modified source.

The normal and fallback sprite atlases are generated locally from the pinned
Mindustry and Arc sources with `:tools:pack`. Exact output hashes and the build
command are recorded in `browser-port/generated-atlas-provenance.json`.
