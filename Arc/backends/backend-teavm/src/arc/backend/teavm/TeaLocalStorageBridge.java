package arc.backend.teavm;

import java.util.List;
import org.teavm.jso.JSBody;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSString;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Bridges the synchronous Java VFS to browser storage (IndexedDB, or
 * localStorage as a fallback) hydrated before startup.
 * <p>
 * Each flush sends every pending journal entry as one commit, which the page
 * applies in a single IndexedDB transaction. Entries are acknowledged only
 * after that transaction completes, so a tab closed mid-save keeps the
 * previous complete files instead of a truncated one.
 */
public final class TeaLocalStorageBridge{
    private static final long maxRetryDelayMillis = 30_000L;

    private static int inFlight;
    private static long inFlightThrough;
    private static int failures;
    private static long retryAt;

    private TeaLocalStorageBridge(){
    }

    /** Hydrates files saved by previous sessions before Arc loads settings and campaign state. */
    public static int hydrate(TeaFileStore store){
        JSArray<JSString> paths = storedPaths();
        int loaded = 0;
        for(int i = 0; i < paths.getLength(); i++){
            String path = paths.get(i).stringValue();
            Int8Array bytes = read(path);
            if(bytes != null){
                store.preload(path, bytes.copyToJavaArray(), (long)readModified(path));
                loaded++;
            }
        }
        releaseHydrationCache();
        return loaded;
    }

    /** Commits pending changes after a browser frame, unless a commit is still running. */
    public static void flush(TeaFileStore store){
        flush(store, false);
    }

    /**
     * Commits pending changes now, even while an earlier commit is running or
     * backing off. Used when the page is being hidden or closed.
     */
    public static void flushNow(TeaFileStore store){
        flush(store, true);
    }

    private static void flush(TeaFileStore store, boolean urgent){
        if(inFlight != 0){
            int status = commitStatus(inFlight);
            if(status == 0 && !urgent) return;
            if(status > 0){
                store.acknowledgeChanges(inFlightThrough);
                failures = 0;
            }else if(status < 0){
                failures++;
                retryAt = System.currentTimeMillis() + Math.min(maxRetryDelayMillis, 1000L << Math.min(failures, 5));
                persistenceWarning();
            }
            inFlight = 0;
        }
        if(!urgent && failures > 0 && System.currentTimeMillis() < retryAt) return;

        // The journal keeps one entry per path (older writes are compacted away),
        // so it can be sent in order as-is.
        List<TeaFileStore.Change> changes = store.snapshotChanges();
        boolean any = false;
        long through = 0L;
        for(TeaFileStore.Change change : changes){
            through = change.getSequence();
            if(change.getType() == TeaFileStore.ChangeType.MKDIR) continue;
            if(!any){
                begin();
                any = true;
            }
            if(change.getType() == TeaFileStore.ChangeType.WRITE){
                stageWrite(change.getPath(), Int8Array.copyFromJavaArray(change.getData()), (double)change.getModified());
            }else{
                stageRemove(change.getPath());
            }
        }
        if(!any){
            // Only directory entries: the browser store keeps files, and parents are inferred on load.
            if(through != 0L) store.acknowledgeChanges(through);
            return;
        }
        int id = commit();
        if(id <= 0){
            failures++;
            retryAt = System.currentTimeMillis() + Math.min(maxRetryDelayMillis, 1000L << Math.min(failures, 5));
            persistenceWarning();
            return;
        }
        inFlight = id;
        inFlightThrough = through;
    }

    @JSBody(script =
        "try { var cache=globalThis.__mindustryVfsCache; return cache ? Array.from(cache.keys()) : []; } catch(e) { return []; }")
    private static native JSArray<JSString> storedPaths();

    @JSBody(params = {"path"}, script =
        "try { var cache=globalThis.__mindustryVfsCache, value=cache&&cache.get(path); return value ? new Int8Array(value.bytes.buffer, value.bytes.byteOffset, value.bytes.byteLength) : null; } catch(e) { return null; }")
    private static native Int8Array read(String path);

    @JSBody(params = {"path"}, script =
        "try { var cache=globalThis.__mindustryVfsCache, value=cache&&cache.get(path); return value ? value.mtime : 0; } catch(e) { return 0; }")
    private static native double readModified(String path);

    /** Java now owns the hydrated bytes; drop the page's copy. */
    @JSBody(script = "globalThis.__mindustryVfsCache = null;")
    private static native void releaseHydrationCache();

    @JSBody(script = "globalThis.__mindustryVfsBegin && globalThis.__mindustryVfsBegin();")
    private static native void begin();

    @JSBody(params = {"path", "bytes", "modified"}, script =
        "globalThis.__mindustryVfsStageWrite(path, new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength), modified);")
    private static native void stageWrite(String path, Int8Array bytes, double modified);

    @JSBody(params = {"path"}, script = "globalThis.__mindustryVfsStageRemove(path);")
    private static native void stageRemove(String path);

    @JSBody(script =
        "try { return globalThis.__mindustryVfsCommit ? globalThis.__mindustryVfsCommit() : 0; } catch(e) { return 0; }")
    private static native int commit();

    @JSBody(params = {"id"}, script =
        "try { return globalThis.__mindustryVfsCommitStatus ? globalThis.__mindustryVfsCommitStatus(id) : -1; } catch(e) { return -1; }")
    private static native int commitStatus(int id);

    @JSBody(script =
        "if(!globalThis.__mindustryPersistenceWarning){globalThis.__mindustryPersistenceWarning=true; console.warn('Mindustry save data could not be persisted: browser storage is unavailable or full.');}"
        + " if(typeof showPersistenceWarning === 'function') showPersistenceWarning(true);")
    private static native void persistenceWarning();
}
