package arc.backend.teavm;

import arc.audio.Audio;
import arc.util.Buffers;
import arc.util.OS;
import arc.util.Threads;
import arc.util.Timer;
import arc.util.io.FastDeflaterOutputStream;

/** Selects Arc's browser-safe core implementations before the rest of the runtime is initialized. */
public final class TeaRuntime{
    private static boolean configured;

    private TeaRuntime(){
    }

    /**
     * Configures the single-threaded browser runtime. This must be the first Arc call made by a TeaVM entry point, before
     * {@code arc.Core}, pixmaps, audio objects, or executor-owning classes are initialized.
     */
    public static synchronized void configure(){
        if(configured) return;

        OS.setWebRuntime(true);
        Buffers.setJavaBufferFallback(true);
        Audio.setJavaFallback(true);
        Threads.setThreadlessExecutorFallback(true);
        Timer.setThreadlessFallback(true);
        FastDeflaterOutputStream.setStreamFactory(TeaDeflaterOutputStream::new);
        configured = true;
    }

    public static boolean isConfigured(){
        return configured;
    }
}
