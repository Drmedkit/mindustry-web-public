package arc.backend.teavm;

import arc.Core;
import arc.audio.Audio;
import arc.audio.Filters;
import arc.graphics.Pixmap;
import arc.util.Buffers;
import arc.util.ArcExecutorService;
import arc.util.ArcFuture;
import arc.util.OS;
import arc.util.Threads;
import arc.util.Timer;

import java.nio.ByteBuffer;

/** Dependency-free JVM integration check for the browser bootstrap ordering. */
public final class TeaRuntimeTest{
    public static void main(String[] args) throws Exception{
        check(!TeaRuntime.isConfigured(), "runtime must remain opt-in");
        TeaRuntime.configure();
        TeaRuntime.configure();

        check(TeaRuntime.isConfigured(), "runtime must report successful configuration");
        check(OS.isWeb && !OS.isIos && !OS.isAndroid, "browser runtime must have an explicit platform identity");
        check(Buffers.isJavaBufferFallback(), "browser runtime must select heap buffers");
        check(Audio.isJavaFallback(), "browser runtime must select silent audio");
        check(Threads.isThreadlessExecutorFallback(), "browser runtime must select threadless executors");
        check(Timer.isThreadlessFallback(), "browser runtime must select main-loop timers");

        Pixmap pixmap = new Pixmap(1, 1);
        check(!pixmap.pixels.isDirect(), "browser pixmaps must use heap storage");
        pixmap.dispose();

        ByteBuffer bytes = Buffers.newUnsafeByteBuffer(4);
        check(!bytes.isDirect(), "browser buffers must use heap storage");
        Buffers.disposeUnsafeByteBuffer(bytes);

        Audio audio = new Audio();
        Filters.BiquadFilter filter = new Filters.BiquadFilter();
        filter.set(0, 500f, 1f);
        check(!audio.initialized(), "browser audio must stay silent until WebAudio is implemented");

        ArcExecutorService executor = Core.executor;
        ArcFuture<Thread> thread = executor.submit(Thread::currentThread);
        check(thread.isDone() && thread.get() == Thread.currentThread(), "Core.executor must not start a browser worker thread");
        executor.shutdown();

        System.out.println("TeaRuntimeTest passed");
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
