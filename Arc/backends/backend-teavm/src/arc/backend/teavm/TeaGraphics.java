package arc.backend.teavm;

import arc.Application.ApplicationType;
import arc.Core;
import arc.Graphics;
import arc.graphics.GL20;
import arc.graphics.GL30;
import arc.graphics.Pixmap;
import arc.graphics.gl.GLVersion;
import arc.util.ArcRuntimeException;
import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLElement;

/**
 * Arc graphics surface backed by an HTML canvas and WebGL.
 *
 * <p>The WebGL bridge is adapted from gdx-teavm's Apache-2.0 backend. Arc
 * remains the authoritative graphics API; this class only owns browser
 * lifecycle state, canvas sizing and the Arc global GL bindings.</p>
 */
public final class TeaGraphics extends Graphics{
    private final HTMLCanvasElement canvas;
    private final TeaWebGLRenderingContext context;
    private GL20 gl20;
    private GL30 gl30;
    private final GLVersion glVersion;
    private final BufferFormat bufferFormat = new BufferFormat(8, 8, 8, 8, 24, 8, 0, false);

    private int logicalWidth;
    private int logicalHeight;
    private long frameId = -1L;
    /** Upper bound for the backbuffer's device pixel ratio; fill rate dominates on weak GPUs. */
    private double maxPixelRatio = 2d;
    /** Frame-rate cap in frames per second, or 0 for the display's refresh rate. */
    private int preferredFps;
    private double lastTimestamp = -1d;
    private float deltaTime = 1f / 60f;
    private float elapsed;
    private int frames;
    private int framesPerSecond;
    private boolean continuous = true;

    public TeaGraphics(String canvasId){
        HTMLElement element = Window.current().getDocument().getElementById(canvasId);
        if(element == null){
            throw new ArcRuntimeException("Browser canvas was not found: #" + canvasId);
        }

        canvas = (HTMLCanvasElement)element;
        TeaWebGLRenderingContext selected = (TeaWebGLRenderingContext)canvas.getContext("webgl2");
        if(selected == null){
            selected = (TeaWebGLRenderingContext)canvas.getContext("webgl");
        }
        if(selected == null){
            throw new ArcRuntimeException("WebGL is unavailable or disabled in this browser.");
        }

        context = selected;
        gl20 = new TeaGL20(context);
        glVersion = new GLVersion(
            ApplicationType.web,
            gl20.glGetString(GL20.GL_VERSION),
            gl20.glGetString(GL20.GL_VENDOR),
            gl20.glGetString(GL20.GL_RENDERER)
        );
        resizeToDisplaySize();
    }

    /** Updates physical canvas pixels from its CSS size and device density. */
    public boolean resizeToDisplaySize(){
        int width = canvas.getClientWidth();
        int height = canvas.getClientHeight();
        if(width <= 0) width = canvas.getWidth();
        if(height <= 0) height = canvas.getHeight();

        logicalWidth = Math.max(width, 1);
        logicalHeight = Math.max(height, 1);
        double density = Math.min(Math.max(Window.current().getDevicePixelRatio(), 1d), maxPixelRatio);
        int backWidth = Math.max((int)Math.round(logicalWidth * density), 1);
        int backHeight = Math.max((int)Math.round(logicalHeight * density), 1);
        boolean changed = canvas.getWidth() != backWidth || canvas.getHeight() != backHeight;
        if(changed){
            canvas.setWidth(backWidth);
            canvas.setHeight(backHeight);
        }
        gl20.glViewport(0, 0, backWidth, backHeight);
        return changed;
    }

    /** Caps the backbuffer resolution relative to CSS pixels (1 = one pixel per CSS pixel). */
    public void setMaxPixelRatio(double ratio){
        maxPixelRatio = Math.max(ratio, 1d);
    }

    @Override
    public void setPreferredFPS(int fps){
        preferredFps = Math.max(fps, 0);
    }

    /**
     * Whether a requestAnimationFrame callback should be skipped to honour the
     * frame-rate cap. A small tolerance keeps a 60 FPS cap from dropping frames
     * on a 60 Hz display whose callbacks arrive slightly early.
     */
    public boolean shouldSkipFrame(double timestampMillis){
        if(preferredFps <= 0 || lastTimestamp < 0d) return false;
        return timestampMillis - lastTimestamp < 1000d / preferredFps - 2d;
    }

    /** Advances delta/FPS accounting at the start of a requestAnimationFrame callback. */
    public void beginFrame(double timestampMillis){
        if(lastTimestamp >= 0d){
            deltaTime = (float)Math.min(Math.max((timestampMillis - lastTimestamp) / 1000d, 0.0001d), 0.25d);
        }
        lastTimestamp = timestampMillis;
        frameId++;
        elapsed += deltaTime;
        frames++;
        if(elapsed >= 1f){
            framesPerSecond = frames;
            frames = 0;
            elapsed %= 1f;
        }
    }

    public HTMLCanvasElement getCanvas(){
        return canvas;
    }

    @Override
    public GL20 getGL20(){
        return gl20;
    }

    @Override
    public void setGL20(GL20 gl20){
        if(gl20 == null) throw new IllegalArgumentException("GL20 cannot be null.");
        this.gl20 = gl20;
        Core.gl = gl20;
        Core.gl20 = gl20;
    }

    @Override
    public GL30 getGL30(){
        return gl30;
    }

    @Override
    public void setGL30(GL30 gl30){
        this.gl30 = gl30;
        Core.gl30 = gl30;
        if(gl30 != null) setGL20(gl30);
    }

    @Override
    public int getWidth(){
        return logicalWidth;
    }

    @Override
    public int getHeight(){
        return logicalHeight;
    }

    @Override
    public int getBackBufferWidth(){
        return canvas.getWidth();
    }

    @Override
    public int getBackBufferHeight(){
        return canvas.getHeight();
    }

    @Override
    public long getFrameId(){
        return frameId;
    }

    @Override
    public float getDeltaTime(){
        return deltaTime;
    }

    @Override
    public int getFramesPerSecond(){
        return framesPerSecond;
    }

    @Override
    public GLVersion getGLVersion(){
        return glVersion;
    }

    @Override
    public float getPpiX(){
        return 96f * (float)Math.max(Window.current().getDevicePixelRatio(), 1d);
    }

    @Override
    public float getPpiY(){
        return getPpiX();
    }

    @Override
    public float getPpcX(){
        return getPpiX() / 2.54f;
    }

    @Override
    public float getPpcY(){
        return getPpiY() / 2.54f;
    }

    @Override
    public float getDensity(){
        return getPpiX() / 160f;
    }

    @Override
    public boolean setFullscreen(boolean fullscreen){
        return setFullscreenNative(canvas, fullscreen);
    }

    @Override
    public void setTitle(String title){
        Window.current().getDocument().setTitle(title);
    }

    @Override
    public void setVSync(boolean vsync){
        // requestAnimationFrame is synchronized by the browser compositor.
    }

    @Override
    public BufferFormat getBufferFormat(){
        return bufferFormat;
    }

    @Override
    public boolean supportsExtension(String extension){
        return context.getExtension(extension) != null;
    }

    @Override
    public boolean isContinuousRendering(){
        return continuous;
    }

    @Override
    public void setContinuousRendering(boolean continuous){
        this.continuous = continuous;
    }

    @Override
    public void requestRendering(){
        // TeaApplication keeps one animation callback scheduled and consults this mode.
    }

    @Override
    public boolean isFullscreen(){
        return isFullscreenNative(canvas);
    }

    @Override
    public Cursor newCursor(Pixmap pixmap, int xHotspot, int yHotspot){
        return new Cursor(){
            @Override
            public void dispose(){
            }
        };
    }

    @Override
    protected void setCursor(Cursor cursor){
        // Custom cursor encoding is deferred; system cursors cover the first slice.
    }

    @Override
    protected void setSystemCursor(Cursor.SystemCursor cursor){
        setCursorNative(canvas, cursorName(cursor));
    }

    @Override
    public void dispose(){
    }

    private static String cursorName(Cursor.SystemCursor cursor){
        switch(cursor){
            case ibeam: return "text";
            case crosshair: return "crosshair";
            case hand: return "pointer";
            case horizontalResize: return "ew-resize";
            case verticalResize: return "ns-resize";
            default: return "default";
        }
    }

    @JSBody(params = {"canvas", "fullscreen"}, script =
        "if(fullscreen){ if(canvas.requestFullscreen){ canvas.requestFullscreen(); return true; } }" +
        "else if(document.fullscreenElement && document.exitFullscreen){ document.exitFullscreen(); return true; }" +
        "return !fullscreen;")
    private static native boolean setFullscreenNative(HTMLCanvasElement canvas, boolean fullscreen);

    @JSBody(params = {"canvas"}, script = "return document.fullscreenElement === canvas;")
    private static native boolean isFullscreenNative(HTMLCanvasElement canvas);

    @JSBody(params = {"canvas", "cursor"}, script = "canvas.style.cursor = cursor;")
    private static native void setCursorNative(HTMLCanvasElement canvas, String cursor);
}
