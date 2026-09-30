import arc.util.*;

import java.lang.reflect.*;

/** Dependency-free JVM checks for {@link Threads}' threadless executor fallback. */
public class ThreadsFallbackCheck{
    public static void main(String[] args) throws Exception{
        check(!Threads.isThreadlessExecutorFallback(), "worker-backed executors must remain the default");
        checkDesktopDefault();

        Threads.setThreadlessExecutorFallback(true);
        try{
            checkFactoriesUseCallingThread();
            checkSerialReentrantExecution();
            checkLifecycle();
            checkCoreInitialization();
            System.out.println("ThreadsFallbackCheck passed");
        }finally{
            Threads.setThreadlessExecutorFallback(false);
        }
    }

    private static void checkDesktopDefault() throws Exception{
        Thread caller = Thread.currentThread();
        ArcExecutorService executor = Threads.executor("Threads fallback desktop check", 1);
        try{
            Thread worker = executor.submit(Thread::currentThread).get(5000L);
            check(worker != caller, "default executor must retain its worker thread");
            check(worker.getName().equals("Threads fallback desktop check"), "default executor must retain thread naming");
        }finally{
            executor.shutdownNow();
            check(executor.awaitTermination(5000L), "default executor did not terminate");
        }
    }

    private static void checkFactoriesUseCallingThread() throws Exception{
        checkCallingThread(Threads.executor("ignored", 4), "fixed executor");
        checkCallingThread(Threads.unboundedExecutor("ignored", 2), "unbounded executor");
        checkCallingThread(Threads.boundedExecutor("ignored", 4), "bounded executor");
    }

    private static void checkCallingThread(ArcExecutorService executor, String label) throws Exception{
        try{
            ArcFuture<Thread> future = executor.submit(Thread::currentThread);
            check(future.isDone(), label + " must finish work before submit returns");
            check(future.get() == Thread.currentThread(), label + " must not create a worker thread");
        }finally{
            executor.shutdown();
        }
    }

    private static void checkSerialReentrantExecution(){
        ArcExecutorService executor = Threads.executor(8);
        StringBuilder order = new StringBuilder();
        executor.execute(() -> {
            order.append('a');
            executor.execute(() -> order.append('c'));
            order.append('b');
        });
        executor.shutdown();

        check(order.toString().equals("abc"), "nested work must be queued serially, got: " + order);
        check(executor.isTerminated(), "drained executor must terminate after shutdown");
    }

    private static void checkLifecycle(){
        ArcExecutorService executor = Threads.executor();
        check(!executor.isShutdown(), "fresh fallback executor must be active");
        executor.shutdown();
        check(executor.isShutdown() && executor.isTerminated(), "idle fallback executor must terminate on shutdown");
        check(executor.shutdownNow().isEmpty(), "terminated fallback executor must have no queued tasks");

        boolean rejected = false;
        try{
            executor.execute(() -> {});
        }catch(ArcRuntimeException expected){
            rejected = true;
        }
        check(rejected, "fallback executor must reject work after shutdown");
    }

    private static void checkCoreInitialization() throws Exception{
        Class<?> coreClass = Class.forName("arc.Core");
        Field field = coreClass.getField("executor");
        ArcExecutorService executor = (ArcExecutorService)field.get(null);
        try{
            ArcFuture<Thread> future = executor.submit(Thread::currentThread);
            check(future.isDone(), "Core.executor must use the fallback selected before Core initialization");
            check(future.get() == Thread.currentThread(), "Core.executor must not create a worker thread in fallback mode");
        }finally{
            executor.shutdown();
        }
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
