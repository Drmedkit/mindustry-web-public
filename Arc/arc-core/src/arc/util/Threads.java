package arc.util;

import arc.*;
import arc.func.*;
import arc.struct.*;

/**
 * Utilities for threaded programming.
 */
public class Threads{
    private static volatile boolean threadlessExecutorFallback;

    /**
     * Makes executor factories return serial executors that run queued work on the calling thread instead of creating worker
     * threads. This is intended for single-threaded runtimes such as browsers and is disabled by default.
     * <p>
     * Enable this before initializing classes with static executor fields, notably {@link Core}. Changing this flag only affects
     * executors created after the change.
     */
    public static void setThreadlessExecutorFallback(boolean enabled){
        threadlessExecutorFallback = enabled;
    }

    /** @return whether newly-created executors use the threadless fallback. */
    public static boolean isThreadlessExecutorFallback(){
        return threadlessExecutorFallback;
    }

    public static <T> ThreadLocal<T> local(Prov<T> prov){
        return new ThreadLocal<T>(){
            @Override
            protected T initialValue(){
                return prov.get();
            }
        };
    }

    public static <T> T await(ArcFuture<T> future){
        return future.get();
    }

    public static void awaitAll(Seq<? extends ArcFuture<?>> futures){
        for(ArcFuture<?> future : futures) future.get();
    }

    /** @return an executor with a fixed number of threads which do not expire */
    public static ArcExecutorService executor(@Nullable String name, int threads){
        return new ArcExecutorService(name, threads, threadlessExecutorFallback);
    }

    /** @return an executor with a fixed number of threads which do not expire */
    public static ArcExecutorService executor(int threads){
        return executor(null, threads);
    }

    /** @see #executor(String, int) */
    public static ArcExecutorService executor(@Nullable String name){
        return executor(name, OS.cores);
    }

    /** @see #executor(String, int) */
    public static ArcExecutorService executor(){
        return executor(null);
    }

    /** @return an executor with no max thread count. threads expire after 1 minute of inactivity
     *  @param min the number of threads to keep alive at all times after they are first started */
    public static ArcExecutorService unboundedExecutor(@Nullable String name, int min){
        return new ArcExecutorService(name, Math.max(min, OS.cores), threadlessExecutorFallback);
    }

    /** @see #unboundedExecutor(String, int) */
    public static ArcExecutorService unboundedExecutor(@Nullable String name){
        return unboundedExecutor(name, 0);
    }

    /** @see #unboundedExecutor(String, int) */
    public static ArcExecutorService unboundedExecutor(){
        return unboundedExecutor(null);
    }

    /** @return an executor with a max thread count. threads expire after 1 minute of inactivity
     *  @param max maximum number of threads to create */
    public static ArcExecutorService boundedExecutor(@Nullable String name, int max){
        return new ArcExecutorService(name, max, threadlessExecutorFallback);
    }

    /** Shuts down the executor and waits for its termination indefinitely. */
    public static void await(ArcExecutorService exec){
        exec.shutdown();
        exec.awaitTermination(Long.MAX_VALUE);
    }

    public static void sleep(long ms){
        try{
            Thread.sleep(ms);
        }catch(InterruptedException e){
            throw new RuntimeException(e);
        }
    }

    public static void sleep(long ms, int ns){
        try{
            // TeaVM supports the millisecond overload. Round any nanosecond remainder up;
            // desktop callers retain the same no-earlier-than behavior.
            Thread.sleep(ms + (ns > 0 ? 1L : 0L));
        }catch(InterruptedException e){
            throw new RuntimeException(e);
        }
    }

    /** Throws an exception in the main game thread.*/
    public static void throwAppException(Throwable t){
        Core.app.post(() -> {
            if(t instanceof RuntimeException){
                throw ((RuntimeException)t);
            }else{
                throw new RuntimeException(t);
            }
        });
    }

    /** Starts a new non-daemon thread.*/
    public static Thread thread(Runnable runnable){
        return thread(null, runnable);
    }

    /** Starts a new non-daemon thread.*/
    public static Thread thread(@Nullable String name, Runnable runnable){
        Thread thread = newThread(runnable, name, false);
        thread.start();
        return thread;
    }

    /** Starts a new daemon thread.*/
    public static Thread daemon(Runnable runnable){
        return daemon(null, runnable);
    }

    /** Starts a new daemon thread.*/
    public static Thread daemon(@Nullable String name, Runnable runnable){
        Thread thread = newThread(runnable, name, true);
        thread.start();
        return thread;
    }

    private static Thread newThread(Runnable r, @Nullable String name, boolean daemon){
        Thread thread = name == null ? new Thread(r) : new Thread(r, name);
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler((t, e) -> Log.err(e));
        return thread;
    }

}
