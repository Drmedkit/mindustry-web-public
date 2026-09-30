package arc.util;

import arc.struct.Queue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Arc-owned task executor for both worker-backed JVM runtimes and single-threaded browsers.
 * It intentionally avoids {@code java.util.concurrent}, which is not part of TeaVM's browser classlib.
 */
public final class ArcExecutorService{
    private final Queue<Runnable> tasks = new Queue<>();
    private final Thread[] workers;
    private final boolean inline;
    private boolean shutdown;
    private boolean runningInline;
    private int stoppedWorkers;

    ArcExecutorService(@Nullable String name, int workerCount, boolean inline){
        this.inline = inline;
        workers = inline ? new Thread[0] : new Thread[Math.max(workerCount, 1)];
        for(int i = 0; i < workers.length; i++){
            Thread worker = name == null ? new Thread(this::workerLoop) : new Thread(this::workerLoop, name);
            worker.setDaemon(true);
            worker.setUncaughtExceptionHandler((thread, error) -> Log.err(error));
            workers[i] = worker;
            worker.start();
        }
    }

    public void shutdown(){
        synchronized(this){
            shutdown = true;
            notifyAll();
        }
    }

    public List<Runnable> shutdownNow(){
        List<Runnable> pending;
        synchronized(this){
            shutdown = true;
            pending = tasks.size == 0 ? Collections.emptyList() : new ArrayList<>(tasks.size);
            while(tasks.size > 0) pending.add(tasks.removeFirst());
            notifyAll();
        }
        for(Thread worker : workers) worker.interrupt();
        return pending;
    }

    public synchronized boolean isShutdown(){
        return shutdown;
    }

    public synchronized boolean isTerminated(){
        return shutdown && tasks.size == 0 && (inline ? !runningInline : stoppedWorkers == workers.length);
    }

    public boolean awaitTermination(long timeoutMillis){
        long remaining = timeoutMillis;
        long deadline = System.currentTimeMillis() + remaining;
        synchronized(this){
            while(!isTerminated() && remaining > 0L){
                try{
                    wait(remaining);
                }catch(InterruptedException error){
                    Thread.currentThread().interrupt();
                    return false;
                }
                remaining = deadline - System.currentTimeMillis();
            }
            return isTerminated();
        }
    }

    public void execute(Runnable command){
        if(command == null) throw new NullPointerException("command");
        if(inline){
            executeInline(command);
        }else{
            synchronized(this){
                rejectIfShutdown();
                tasks.addLast(command);
                notifyAll();
            }
        }
    }

    public <T> ArcFuture<T> submit(Callable<T> task){
        if(task == null) throw new NullPointerException("task");
        Task<T> future = new Task<>(task);
        execute(future);
        return future;
    }

    public <T> ArcFuture<T> submit(Runnable task, T result){
        if(task == null) throw new NullPointerException("task");
        return submit(() -> {
            task.run();
            return result;
        });
    }

    public ArcFuture<?> submit(Runnable task){
        return submit(task, null);
    }

    private void executeInline(Runnable command){
        synchronized(this){
            rejectIfShutdown();
            tasks.addLast(command);
            if(runningInline) return;
            runningInline = true;
        }

        Throwable failure = null;
        while(true){
            Runnable next;
            synchronized(this){
                if(tasks.size == 0){
                    runningInline = false;
                    notifyAll();
                    break;
                }
                next = tasks.removeFirst();
            }
            try{
                next.run();
            }catch(RuntimeException | Error error){
                if(failure == null) failure = error;
            }
        }
        if(failure instanceof RuntimeException) throw (RuntimeException)failure;
        if(failure != null) throw (Error)failure;
    }

    private void workerLoop(){
        try{
            while(true){
                Runnable next;
                synchronized(this){
                    while(tasks.size == 0 && !shutdown) wait();
                    if(tasks.size == 0 && shutdown) return;
                    next = tasks.removeFirst();
                }
                try{
                    next.run();
                }catch(Throwable error){
                    Log.err(error);
                }
            }
        }catch(InterruptedException ignored){
            Thread.currentThread().interrupt();
        }finally{
            synchronized(this){
                stoppedWorkers++;
                notifyAll();
            }
        }
    }

    private void rejectIfShutdown(){
        if(shutdown) throw new ArcRuntimeException("Executor has been shut down.");
    }

    private static final class Task<T> implements ArcFuture<T>, Runnable{
        private final Callable<T> callable;
        private boolean cancelled;
        private boolean done;
        private T result;
        private Throwable failure;

        Task(Callable<T> callable){
            this.callable = callable;
        }

        @Override
        public synchronized boolean cancel(){
            if(done) return false;
            cancelled = true;
            done = true;
            notifyAll();
            return true;
        }

        @Override
        public synchronized boolean isCancelled(){
            return cancelled;
        }

        @Override
        public synchronized boolean isDone(){
            return done;
        }

        @Override
        public void run(){
            synchronized(this){
                if(done) return;
            }
            T value = null;
            Throwable thrown = null;
            try{
                value = callable.call();
            }catch(Throwable error){
                thrown = error;
            }
            synchronized(this){
                if(!done){
                    result = value;
                    failure = thrown;
                    done = true;
                    notifyAll();
                }
            }
        }

        @Override
        public T get(){
            return get(Long.MAX_VALUE);
        }

        @Override
        public synchronized T get(long timeoutMillis){
            long remaining = timeoutMillis;
            long deadline = timeoutMillis == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + timeoutMillis;
            while(!done && remaining > 0L){
                try{
                    wait(remaining);
                }catch(InterruptedException error){
                    Thread.currentThread().interrupt();
                    throw new ArcRuntimeException(error);
                }
                remaining = deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.currentTimeMillis();
            }
            if(!done) throw new ArcRuntimeException("Timed out waiting for task completion.");
            if(cancelled) throw new ArcRuntimeException("Task was cancelled.");
            if(failure != null) throw new ArcRuntimeException(failure);
            return result;
        }
    }
}
