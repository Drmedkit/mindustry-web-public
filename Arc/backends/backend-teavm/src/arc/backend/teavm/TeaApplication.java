package arc.backend.teavm;

import arc.Application;
import arc.ApplicationListener;
import arc.Core;
import arc.Settings;
import arc.audio.Audio;
import arc.func.Boolp;
import arc.struct.Queue;
import arc.struct.Seq;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Window;

/** Arc application lifecycle driven by the browser animation and visibility APIs. */
public final class TeaApplication implements Application{
    private final Seq<ApplicationListener> listeners = new Seq<>();
    private final Queue<Runnable> posted = new Queue<>();
    private final TeaFiles files;
    private final TeaGraphics graphics;
    private final TeaInput input;
    private boolean running = true;
    private boolean paused;
    /** While true, updates continue in a hidden tab (browsers stop animation frames there). */
    private Boolp backgroundUpdates = () -> false;
    private final Scheduler backgroundScheduler = createBackgroundScheduler(this::backgroundFrame);
    private double lastBackgroundFrame;
    /** Only the most recently scheduled frame runs, so switching tabs never starts a second loop. */
    private int frameRequest;
    private int backgroundRequest;
    private double lastFrameTime;
    private boolean watchdogScheduled;
    private String clipboard = "";

    public TeaApplication(String canvasId, ApplicationListener listener){
        this(canvasId, listener, new TeaFiles());
    }

    public TeaApplication(String canvasId, ApplicationListener listener, TeaFiles files){
        if(listener == null) throw new IllegalArgumentException("Application listener cannot be null.");
        if(files == null) throw new IllegalArgumentException("Browser files cannot be null.");

        TeaRuntime.configure();
        this.files = files;
        graphics = new TeaGraphics(canvasId);
        input = new TeaInput(graphics);
        listeners.add(listener);

        Core.app = this;
        Core.files = files;
        Core.graphics = graphics;
        Core.input = input;
        //The pure-Java fallback is silent until the WebAudio module is installed.
        Core.audio = new Audio();
        Core.settings = new Settings();
        Core.settings.setDataDirectory(files.local("data"));
        Core.settings.load();
        Core.gl = graphics.getGL20();
        Core.gl20 = graphics.getGL20();
        Core.gl30 = graphics.getGL30();

        Window.current().getDocument().addEventListener("visibilitychange", event -> visibilityChanged(isDocumentHidden()));
        // Closing, reloading or leaving the page: save and commit, but keep the app
        // alive so a page restored from the back/forward cache resumes normally.
        Window.current().addEventListener("pagehide", event -> pageHidden());
        // Text pasted from outside the game (e.g. a shared schematic) becomes the clipboard.
        Window.current().addEventListener("paste", event -> {
            String text = pastedText(event);
            if(text != null) clipboard = text;
            input.pasted();
        });

        listener.init();
        listener.resize(graphics.getWidth(), graphics.getHeight());
        // A page opened in a background tab starts paused, like one hidden later.
        paused = isDocumentHidden();
        scheduleFrame();
    }

    /**
     * Keeps the application updating while the tab is hidden, as long as the
     * condition holds; for example while assets load, or for a multiplayer host.
     */
    public void setBackgroundUpdates(Boolp condition){
        backgroundUpdates = condition == null ? () -> false : condition;
        // Re-pick the loop: a page that started hidden has an animation frame pending that will not fire.
        if(running) scheduleFrame();
    }

    private boolean updatesInBackground(){
        return paused && backgroundUpdates.get();
    }

    private void scheduleFrame(){
        int id = ++frameRequest;
        if(updatesInBackground()){
            backgroundRequest = id;
            backgroundScheduler.schedule();
        }else{
            Window.requestAnimationFrame(timestamp -> {
                if(id == frameRequest) frame(timestamp);
            });
        }
    }

    /**
     * A visible page can still get very few animation frames (a covered window,
     * an inactive tab in some browsers). While background updates are wanted,
     * run a frame from a timer whenever animation frames stall.
     */
    private void scheduleWatchdog(){
        if(watchdogScheduled) return;
        watchdogScheduled = true;
        Window.setTimeout(this::watchdog, 50);
    }

    private void watchdog(){
        watchdogScheduled = false;
        if(!running || !backgroundUpdates.get()) return;
        double now = now();
        if(!paused && now - lastFrameTime > 100d) frame(now);
        scheduleWatchdog();
    }

    private void backgroundFrame(double timestampMillis){
        if(!running || backgroundRequest != frameRequest) return;
        // A message-channel loop is not throttled in hidden tabs; pace it to roughly 60 updates per second.
        if(!updatesInBackground() || timestampMillis - lastBackgroundFrame >= 16d){
            lastBackgroundFrame = timestampMillis;
            frame(timestampMillis);
        }else{
            backgroundScheduler.schedule();
        }
    }

    private void frame(double timestampMillis){
        if(!running) return;
        if(!paused && graphics.shouldSkipFrame(timestampMillis)){
            scheduleFrame();
            return;
        }

        lastFrameTime = now();
        if(backgroundUpdates.get()) scheduleWatchdog();
        boolean resized = graphics.resizeToDisplaySize();
        graphics.beginFrame(timestampMillis);
        try{
            drainPosted();
            if(resized){
                for(ApplicationListener listener : listeners) listener.resize(graphics.getWidth(), graphics.getHeight());
            }
            if(!paused || updatesInBackground()){
                defaultUpdate();
                for(ApplicationListener listener : listeners) listener.update();
                input.postUpdate();
            }
        }catch(Throwable error){
            running = false;
            TeaLocalStorageBridge.flushNow(files.getLocalStore());
            reportFailure(describe(error));
            return;
        }
        TeaLocalStorageBridge.flush(files.getLocalStore());
        scheduleFrame();
    }

    private static String describe(Throwable error){
        StringBuilder out = new StringBuilder();
        Throwable current = error;
        for(int depth = 0; current != null && depth < 8; depth++){
            if(depth > 0) out.append(" <- ");
            out.append(current.getClass().getName());
            if(current.getMessage() != null) out.append(": ").append(current.getMessage());
            current = current.getCause();
        }
        StackTraceElement[] trace = error.getStackTrace();
        for(int i = 0; i < trace.length && i < 12; i++) out.append("\n at ").append(trace[i]);
        return out.toString();
    }

    @JSBody(params = {"message"}, script =
        "var node = document.getElementById('boot-status'); if(node){ node.textContent = 'Boot error: ' + message + '\\nJavaScript stack: ' + (new Error()).stack; node.dataset.state = 'error'; }")
    private static native void reportFailure(String message);

    private void drainPosted(){
        while(true){
            Runnable next;
            synchronized(posted){
                if(posted.size == 0) return;
                next = posted.removeFirst();
            }
            next.run();
        }
    }

    private void visibilityChanged(boolean hidden){
        if(hidden == paused || !running) return;
        paused = hidden;
        for(ApplicationListener listener : listeners){
            if(hidden) listener.pause();
            else listener.resume();
        }
        if(hidden) TeaLocalStorageBridge.flushNow(files.getLocalStore());
        // Animation frames stop in hidden tabs: switch loops (this supersedes the pending frame).
        scheduleFrame();
    }

    private void pageHidden(){
        if(!running) return;
        // visibilitychange normally fires first; pause (which saves) if it did not.
        if(!paused) visibilityChanged(true);
        else TeaLocalStorageBridge.flushNow(files.getLocalStore());
    }

    public TeaFiles getFiles(){
        return files;
    }

    public TeaGraphics getGraphics(){
        return graphics;
    }

    public TeaInput getInput(){
        return input;
    }

    @Override
    public Seq<ApplicationListener> getListeners(){
        return listeners;
    }

    @Override
    public ApplicationType getType(){
        return ApplicationType.web;
    }

    @Override
    public String getClipboardText(){
        return clipboard;
    }

    @Override
    public void setClipboardText(String text){
        clipboard = text == null ? "" : text;
        writeClipboard(clipboard);
    }

    @Override
    public boolean openURI(String uri){
        return openUriNative(uri);
    }

    @Override
    public void post(Runnable runnable){
        if(runnable == null) throw new NullPointerException("runnable");
        synchronized(posted){
            posted.addLast(runnable);
        }
    }

    @Override
    public void exit(){
        if(!running) return;
        running = false;
        for(ApplicationListener listener : listeners){
            listener.exit();
            if(!paused) listener.pause();
            listener.dispose();
        }
        dispose();
    }

    @Override
    public void dispose(){
        if(Core.settings != null) Core.settings.autosave();
        TeaLocalStorageBridge.flushNow(files.getLocalStore());
        graphics.dispose();
    }

    @JSBody(params = {"event"}, script =
        "var data = event.clipboardData; return data ? data.getData('text/plain') : null;")
    private static native String pastedText(org.teavm.jso.dom.events.Event event);

    /** Runs a callback on a later task via a MessageChannel, which hidden tabs do not throttle. */
    @JSFunctor
    interface Scheduler extends JSObject{
        void schedule();
    }

    @JSFunctor
    interface FrameCallback extends JSObject{
        void run(double timestampMillis);
    }

    @JSBody(params = {"callback"}, script =
        "var channel = new MessageChannel(); channel.port1.onmessage = function(){ callback(performance.now()); };"
        + "return function(){ channel.port2.postMessage(0); };")
    private static native Scheduler createBackgroundScheduler(FrameCallback callback);

    @JSBody(script = "return performance.now();")
    private static native double now();

    @JSBody(script = "return document.visibilityState === 'hidden';")
    private static native boolean isDocumentHidden();

    @JSBody(params = {"text"}, script =
        "if(navigator.clipboard && navigator.clipboard.writeText){ navigator.clipboard.writeText(text).catch(function(){}); }")
    private static native void writeClipboard(String text);

    @JSBody(params = {"uri"}, script = "window.open(uri, '_blank', 'noopener'); return true;")
    private static native boolean openUriNative(String uri);
}
