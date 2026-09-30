package io.mindustry.browser.probe;

import arc.Core;
import arc.ApplicationListener;
import arc.backend.teavm.TeaFiles;
import arc.backend.teavm.TeaApplication;
import arc.backend.teavm.TeaRuntime;
import arc.graphics.GL20;
import arc.math.Mathf;
import arc.struct.Seq;
import org.teavm.jso.JSBody;

/**
 * Browser-shell proof that TeaVM can execute Arc core, VFS and GL calls.
 */
public final class ArcProbe {
    private ArcProbe() {
    }

    public static void main(String[] args) {
        TeaRuntime.configure();

        Seq<String> sectors = Seq.with("Ground Zero", "Frozen Forest", "The Craters");
        int selected = Mathf.clamp(0, 0, sectors.size - 1);
        TeaFiles files = new TeaFiles();
        files.getAssetStore().preload("maps/serpulo/groundZero.msav", new byte[]{6, 18});
        files.local("saves/browser-probe.dat").writeBytes(new byte[]{1});

        try{
            new TeaApplication("game-canvas", new ApplicationListener(){
                int frames;

                @Override
                public void update(){
                    Core.gl.glClearColor(0.055f, 0.075f, 0.082f, 1f);
                    Core.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                    if(frames++ != 1) return;

                    showResult(
                        "TeaVM is executing Arc core. First Serpulo sector: " + sectors.get(selected)
                            + ". Arc Seq size: " + sectors.size
                            + ". VFS map bytes: " + files.internal("maps/serpulo/groundZero.msav").length()
                            + ". Pending save changes: " + files.getLocalStore().snapshotChanges().size()
                            + ". GL: " + Core.graphics.getGLVersion().type
                            + ". Canvas: " + Core.graphics.getBackBufferWidth() + "x" + Core.graphics.getBackBufferHeight()
                            + ". Lifecycle frames: " + frames + ". Input: " + (Core.input != null) + "."
                    );
                }
            }, files);
        }catch(Throwable error){
            showResult("Browser lifecycle probe failed: " + error.getClass().getName() + ": " + error.getMessage());
        }
    }

    @JSBody(
        params = {"message"},
        script = "document.getElementById('probe-status').textContent = message;"
    )
    private static native void showResult(String message);
}
