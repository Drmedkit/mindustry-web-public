package io.mindustry.browser;

import arc.Core;
import arc.Events;
import arc.backend.teavm.TeaApplication;
import arc.backend.teavm.TeaAssetBridge;
import arc.backend.teavm.TeaFiles;
import arc.backend.teavm.TeaGraphics;
import arc.backend.teavm.TeaLocalStorageBridge;
import arc.backend.teavm.TeaRuntime;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import io.mindustry.browser.net.RelayNetProvider;
import mindustry.ClientLauncher;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.content.SectorPresets;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.mod.Scripts;
import mindustry.net.Net.NetProvider;
import mindustry.type.SectorPreset;
import mindustry.world.BuiltinBlockBuildFactories;
import org.teavm.jso.JSBody;

/** Browser entry point for the actual Mindustry client. */
public final class MindustryBrowserLauncher{
    private MindustryBrowserLauncher(){
    }

    public static void main(String[] args){
        try{
            TeaRuntime.configure();
            BuiltinBlockBuildFactories.install();

            TeaFiles files = new TeaFiles();
            TeaAssetBridge.populate(files.getAssetStore());
            TeaLocalStorageBridge.hydrate(files.getLocalStore());
            BrowserClient client = new BrowserClient();
            TeaApplication app = new TeaApplication("game-canvas", client, files);
            // Keep loading while the player looks at another tab; the game itself pauses when hidden.
            // A game with other players must keep running too: the host serves them, and a
            // silent guest would time out.
            app.setBackgroundUpdates(() -> !client.ready || Vars.net.active());
        }catch(Throwable error){
            showBootError(error.getClass().getName() + ": " + error.getMessage());
        }
    }

    @JSBody(params = {"message"}, script =
        "var node = document.getElementById('boot-status'); node.textContent = 'Boot error: ' + message; node.dataset.state = 'error';")
    private static native void showBootError(String message);

    @JSBody(params = {"message"}, script =
        "var node = document.getElementById('boot-status'); node.textContent = message; node.dataset.state = 'loading';")
    private static native void showBootStage(String message);

    @JSBody(params = {"message"}, script =
        "var node = document.getElementById('boot-status'); node.textContent = message; node.dataset.state = 'ready';" +
        "setTimeout(function(){ node.hidden = true; document.body.dataset.playing = ''; }, 1500);")
    private static native void showGameReady(String message);

    @JSBody(script =
        "var local = location.hostname === '127.0.0.1' || location.hostname === 'localhost';" +
        "return local && new URLSearchParams(location.search).get('validate-serpulo') === '1';")
    private static native boolean validateSerpuloRequested();

    @JSBody(script =
        "var local = location.hostname === '127.0.0.1' || location.hostname === 'localhost';" +
        "return local && new URLSearchParams(location.search).get('dump-content') === '1';")
    private static native boolean dumpContentRequested();

    @JSBody(params = {"json"}, script =
        "var node = document.createElement('pre'); node.id = 'content-dump'; node.hidden = true; node.textContent = json; document.body.append(node);")
    private static native void showContentDump(String json);

    @JSBody(params = {"count", "problems"}, script =
        "var node = document.getElementById('boot-status'); node.textContent = 'Validated ' + count + ' Serpulo campaign sectors'"
        + " + (problems ? '; sprites on never-downloaded pages: ' + problems : '; no sprites on never-downloaded pages');" +
        "node.dataset.validationCount = String(count); node.dataset.validationProblems = problems; node.dataset.state = 'ready';")
    private static native void showValidationReady(int count, String problems);

    @JSBody(script = "return !!(globalThis.mindustryDevice && globalThis.mindustryDevice.lowEnd);")
    private static native boolean lowEndDevice();

    @JSBody(script = "var code = new URLSearchParams(location.search).get('room'); return code ? code : '';")
    private static native String requestedRoom();

    /** Co-op relay origin: same origin as the page, or ?relay=ws://... for local testing. */
    @JSBody(script = "var relay = new URLSearchParams(location.search).get('relay');"
        + "return relay || ((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host);")
    private static native String relayUrl();

    @JSBody(script = "return String(globalThis.mindustryBuild || 'dev');")
    private static native String buildId();

    /** Keeps desktop networking and JVM mod scripting outside TeaVM's reachable graph. */
    private static final class BrowserClient extends ClientLauncher{
        private Seq<SectorPreset> validationSectors;
        private int validationIndex;
        private final arc.struct.ObjectSet<String> validationProblems = new arc.struct.ObjectSet<>();
        private final String roomCode = RelayNetProvider.normalizeCode(requestedRoom());
        private RelayNetProvider relay;
        /** Set once the first playable screen is up; until then loading continues in a hidden tab. */
        boolean ready;

        BrowserClient(){
            Events.on(ClientLoadEvent.class, event -> {
                TeaAssetBridge.releaseDecodedPixels();
                SerpuloOnly.afterClientLoad();
                if(dumpContentRequested()) showContentDump(SerpuloOnly.contentSnapshot());
                Log.info("Browser campaign startup: Ground Zero sector @, captured=@.",
                    SectorPresets.groundZero.sector.id, SectorPresets.groundZero.sector.info.wasCaptured);

                if(roomCode != null){
                    CoopUi.install();
                    showBootStage("Joining co-op room " + roomCode + "...");
                    // "ready" is set when the host's world loads, so a hidden tab keeps connecting.
                    Core.app.post(() -> CoopUi.joinFromLink(roomCode));
                }else if(validateSerpuloRequested()){
                    validationSectors = Vars.content.sectors().select(preset -> preset.planet == Planets.serpulo);
                    validationSectors.sortComparing(preset -> preset.name);
                    validationIndex = 0;
                    loadValidationSector();
                }else if(SectorPresets.groundZero.sector.info.wasCaptured){
                    CoopUi.install();
                    showBootStage("Opening the Serpulo campaign map…");
                    Core.app.post(() -> {
                        Vars.ui.planet.state.planet = Planets.serpulo;
                        Vars.ui.planet.show();
                        showGameReady("Serpulo campaign map ready");
                        ready = true;
                    });
                }else{
                    CoopUi.install();
                    showBootStage("Loading Serpulo: " + SectorPresets.groundZero.sector.name() + "…");
                    Core.app.post(() -> Vars.control.playSector(SectorPresets.groundZero.sector));
                }
            });
            Events.on(WorldLoadEvent.class, event -> {
                if(!SerpuloOnly.acceptWorld()){
                    Core.app.post(() -> {
                        Vars.logic.reset();
                        Vars.ui.showInfo("This map is made for another planet. Only Serpulo is available in the browser build.");
                    });
                    return;
                }
                if(validationSectors == null){
                    showGameReady("Serpulo campaign ready");
                    ready = true;
                    return;
                }

                validationProblems.addAll(SerpuloOnly.worldBlocksOnPlaceholders());
                int completed = validationIndex + 1;
                if(completed >= validationSectors.size){
                    validationProblems.addAll(SerpuloOnly.visibleContentOnPlaceholders());
                    showValidationReady(completed, validationProblems.toSeq().sort().toString(", "));
                }else{
                    validationIndex = completed;
                    Time.runTask(2f, this::loadValidationSector);
                }
            });
        }

        @Override
        public void init(){
            applyGraphicsPreset();
            super.init();
        }

        /**
         * First run only: choose graphics settings for this device. Players can
         * change every one of them in Settings afterwards.
         */
        private void applyGraphicsPreset(){
            boolean lowEnd = lowEndDevice();
            if(!Core.settings.has("web-graphics-preset")){
                Core.settings.put("web-graphics-preset", lowEnd ? "low" : "normal");
                // Rendering every refresh of a 120-144 Hz screen doubles the work for no gameplay benefit.
                Core.settings.put("fpscap", lowEnd ? 30 : 60);
                if(lowEnd){
                    Core.settings.put("bloom", false);
                    Core.settings.put("animatedwater", false);
                    Core.settings.put("animatedshields", false);
                    Core.settings.put("atmosphere", false);
                    Core.settings.put("showweather", false);
                }
            }
            // Fill rate is the bottleneck on weak GPUs: a 2x screen costs about 3x the frame time.
            if(Core.graphics instanceof TeaGraphics graphics){
                graphics.setMaxPixelRatio("low".equals(Core.settings.getString("web-graphics-preset", "normal")) ? 1d : 2d);
            }
        }

        private int appliedFpsCap = -1;

        @Override
        public void update(){
            // ClientLauncher cannot sleep in a browser; the backend skips animation frames instead.
            int fpsCap = Core.settings.getInt("fpscap", 60);
            if(fpsCap != appliedFpsCap){
                appliedFpsCap = fpsCap;
                Core.graphics.setPreferredFPS(fpsCap > 240 ? 0 : fpsCap);
            }
            super.update();
            // Send this frame's packets as one message per player.
            if(relay != null) relay.flush();
        }

        private void loadValidationSector(){
            SectorPreset preset = validationSectors.get(validationIndex);
            showBootStage("Validating Serpulo " + (validationIndex + 1) + "/" + validationSectors.size + ": " + preset.name);
            Core.app.post(() -> Vars.control.playSector(preset.sector));
        }

        @Override
        public NetProvider getNet(){
            relay = new RelayNetProvider(relayUrl(), buildId(), CoopUi.instance);
            CoopUi.relay = relay;
            return relay;
        }

        @Override
        public Scripts createScripts(){
            throw new UnsupportedOperationException("JVM mod scripting is unavailable in the browser build.");
        }

        @Override
        protected void loadModScripts(){
            // Runs right after base content is created, before planet meshes are built.
            SerpuloOnly.afterContentCreated();
        }

        @Override
        protected void queueModAssets(){
        }

        @Override
        protected void initializeMods(){
        }

        @Override
        public void pause(){
            // A browser refresh/page close can happen before Mindustry's periodic autosave.
            // Serialize the active world synchronously while pagehide still permits local work.
            if(Vars.state != null && Vars.state.isGame() && Vars.control != null && Vars.control.saves != null){
                var current = Vars.control.saves.getCurrent();
                if(current != null && !Vars.disableSave) current.save();
            }
            // Research and sector progress live in settings, not in the world save.
            if(Core.settings != null && Core.settings.modified()) Core.settings.forceSave();
            super.pause();
        }
    }
}
