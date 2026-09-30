from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class BrowserSourceContractsTest(unittest.TestCase):
    def source(self, relative_path: str) -> str:
        return (ROOT / relative_path).read_text(encoding="utf-8")

    def test_async_core_uses_arc_executor_factory(self):
        source = self.source(
            "Mindustry/core/src/mindustry/async/AsyncCore.java"
        )

        self.assertIn(
            'Threads.executor("AsyncLogic-Thread", Math.max(processes.size, 1))',
            source,
        )
        self.assertNotIn("Executors.newFixedThreadPool", source)

    def test_client_loop_does_not_sleep_to_limit_browser_fps(self):
        source = self.source("Mindustry/core/src/mindustry/ClientLauncher.java")

        self.assertIn(
            "boolean limitFps = !Core.app.isWeb() && targetfps > 0",
            source,
        )

    def test_web_platform_uses_socket_free_networking(self):
        platform = self.source("Mindustry/core/src/mindustry/core/Platform.java")
        jvm_services = self.source(
            "Mindustry/core/src/mindustry/core/JvmPlatformServices.java"
        )

        self.assertIn("return new OfflineNetProvider()", platform)
        self.assertNotIn("new ArcNetProvider()", platform)
        self.assertIn("return new ArcNetProvider()", jvm_services)

    def test_web_runtime_disables_jvm_mod_code(self):
        vars_source = self.source("Mindustry/core/src/mindustry/Vars.java")
        mods_source = self.source("Mindustry/core/src/mindustry/mod/Mods.java")

        self.assertIn("if(OS.isWeb)", vars_source)
        self.assertIn("skipModCode = true", vars_source)
        self.assertIn("if(OS.isWeb || skipModCode) return", mods_source)

    def test_active_world_is_saved_and_flushed_when_browser_hides(self):
        launcher = self.source(
            "browser-game/src/main/java/io/mindustry/browser/MindustryBrowserLauncher.java"
        )
        application = self.source(
            "Arc/backends/backend-teavm/src/arc/backend/teavm/TeaApplication.java"
        )

        self.assertIn("public void pause()", launcher)
        self.assertIn("current.save()", launcher)
        self.assertIn(
            "if(hidden) TeaLocalStorageBridge.flushNow(files.getLocalStore())",
            application,
        )
        # pagehide saves and commits without tearing the app down.
        self.assertIn('addEventListener("pagehide", event -> pageHidden())', application)

    def test_browser_key_repeat_does_not_retrigger_key_taps(self):
        source = self.source(
            "Arc/backends/backend-teavm/src/arc/backend/teavm/TeaInput.java"
        )

        self.assertIn("private final boolean[] pressedKeys", source)
        self.assertIn("if(!pressedKeys[key.ordinal()])", source)
        self.assertIn("pressedKeys[key.ordinal()] = false", source)

    def test_browser_opens_ground_zero_until_it_has_been_captured(self):
        source = self.source(
            "browser-game/src/main/java/io/mindustry/browser/MindustryBrowserLauncher.java"
        )

        self.assertIn("SectorPresets.groundZero.sector.info.wasCaptured", source)
        self.assertIn("Vars.ui.planet.state.planet = Planets.serpulo", source)
        self.assertIn("Vars.ui.planet.show()", source)
        self.assertIn(
            "Vars.control.playSector(SectorPresets.groundZero.sector)", source
        )
        self.assertNotIn("Vars.control.saves.getLastSector()", source)

    def test_browser_exposes_only_serpulo_on_the_campaign_map(self):
        launcher = self.source(
            "browser-game/src/main/java/io/mindustry/browser/MindustryBrowserLauncher.java"
        )
        planet_dialog = self.source(
            "Mindustry/core/src/mindustry/ui/dialogs/PlanetDialog.java"
        )

        serpulo_only = self.source(
            "browser-game/src/main/java/io/mindustry/browser/SerpuloOnly.java"
        )
        custom_rules = self.source(
            "Mindustry/core/src/mindustry/ui/dialogs/CustomRulesDialog.java"
        )

        # Erekir stays registered (content IDs must match vanilla for multiplayer) but is hidden.
        self.assertIn("SerpuloOnly.afterContentCreated()", launcher)
        self.assertIn("SerpuloOnly.afterClientLoad()", launcher)
        self.assertIn("SerpuloOnly.acceptWorld()", launcher)
        self.assertIn("planet.accessible = false", serpulo_only)
        self.assertIn("planet.visible = false", serpulo_only)
        self.assertIn("u.hideDatabase = true", serpulo_only)
        self.assertIn("block.inEditor = false", serpulo_only)
        self.assertNotIn("content.remove", serpulo_only)
        self.assertIn('Core.settings.put("campaignselect", true)', serpulo_only)
        self.assertIn('Core.settings.put("lastplanet", Planets.serpulo.name)', serpulo_only)
        self.assertIn("return planet.accessible &&", planet_dialog)
        self.assertIn('if(Planets.erekir.accessible) t.button("@rules.anyenv"', custom_rules)

    def test_campaign_geometry_uses_reflection_free_json_constructors(self):
        json_source = self.source(
            "Arc/arc-core/src/arc/util/serialization/Json.java"
        )
        json_io_source = self.source("Mindustry/core/src/mindustry/io/JsonIO.java")
        reflection_source = self.source(
            "browser-game/src/main/java/io/mindustry/browser/MindustryReflectionSupplier.java"
        )

        self.assertIn("setInstantiator(Class<T> type", json_source)
        self.assertIn("json.setInstantiator(Point2.class, Point2::new)", json_io_source)
        self.assertIn("json.setInstantiator(Vec2.class, Vec2::new)", json_io_source)
        self.assertIn("json.setInstantiator(Vec3.class, Vec3::new)", json_io_source)
        self.assertIn("json.setInstantiator(Seq.class, Seq::new)", json_io_source)
        self.assertIn(
            "json.setInstantiator(ObjectSet.class, ObjectSet::new)",
            json_io_source,
        )
        self.assertIn(
            "json.setInstantiator(Rules.TeamRules.class, Rules.TeamRules::new)",
            json_io_source,
        )
        self.assertIn(
            "json.setInstantiator(SectorInfo.class, SectorInfo::new)", json_io_source
        )
        self.assertIn(
            "json.setInstantiator(ItemSeq.class, ItemSeq::new)", json_io_source
        )
        self.assertIn(
            "json.setInstantiator(PlanetParams.class, PlanetParams::new)",
            json_io_source,
        )
        self.assertIn('name.equals("mindustry.game.SectorInfo")', reflection_source)
        self.assertIn(
            'name.startsWith("mindustry.game.SectorInfo$")', reflection_source
        )

    def test_never_scaling_campaign_waves_serialize_without_infinity(self):
        source = self.source("Mindustry/core/src/mindustry/game/SpawnGroup.java")

        self.assertIn(
            "unitScaling != never && Float.isFinite(unitScaling)",
            source,
        )

    def test_campaign_saves_use_browser_owned_zlib_stream(self):
        core_source = self.source(
            "Arc/arc-core/src/arc/util/io/FastDeflaterOutputStream.java"
        )
        runtime_source = self.source(
            "Arc/backends/backend-teavm/src/arc/backend/teavm/TeaRuntime.java"
        )
        browser_source = self.source(
            "Arc/backends/backend-teavm/src/arc/backend/teavm/TeaDeflaterOutputStream.java"
        )

        self.assertIn("setStreamFactory", core_source)
        self.assertIn("TeaDeflaterOutputStream::new", runtime_source)
        self.assertIn("deflater.avail_in > 0", browser_source)
        self.assertIn("JZlib.Z_FINISH", browser_source)

        logic_source = self.source(
            "Mindustry/core/src/mindustry/world/blocks/logic/LogicBlock.java"
        )
        schematics_source = self.source(
            "Mindustry/core/src/mindustry/game/Schematics.java"
        )
        self.assertIn("new FastDeflaterOutputStream(baos)", logic_source)
        self.assertIn("new FastDeflaterOutputStream(output)", schematics_source)

    def test_browser_vfs_uses_indexeddb_and_migrates_legacy_saves(self):
        storage_source = self.source("browser-game/src/main/webapp/vfs-storage.js")
        bridge_source = self.source(
            "Arc/backends/backend-teavm/src/arc/backend/teavm/TeaLocalStorageBridge.java"
        )
        index_source = self.source("browser-game/src/main/webapp/index.html")

        self.assertIn("indexedDB.open('mindustry-browser-vfs', 1)", storage_source)
        self.assertIn("migrateLegacyVfs", storage_source)
        self.assertIn("__mindustryVfsWaitForIdle", storage_source)
        self.assertIn("__mindustryVfsCache", bridge_source)
        self.assertIn("preloadMindustryStorage()", index_source)


if __name__ == "__main__":
    unittest.main()
