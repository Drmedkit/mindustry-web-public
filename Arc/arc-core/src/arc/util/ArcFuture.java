package arc.util;

/** Minimal future contract shared by JVM worker pools and the browser event loop. */
public interface ArcFuture<T>{
    boolean cancel();

    boolean isCancelled();

    boolean isDone();

    T get();

    T get(long timeoutMillis);
}
