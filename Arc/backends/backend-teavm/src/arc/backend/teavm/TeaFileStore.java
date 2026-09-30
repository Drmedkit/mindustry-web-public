package arc.backend.teavm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A small synchronous, in-memory filesystem used by the browser backend.
 * <p>
 * The store deliberately has no Arc or TeaVM dependencies. Browser startup can
 * preload it from packaged assets and IndexedDB, while mutations can be flushed
 * asynchronously by consuming the ordered change journal.
 * <p>
 * All paths are case-sensitive, relative POSIX paths. Empty paths and {@code .}
 * address the root directory. Absolute paths, backslashes and every
 * {@code ..} segment are rejected instead of being resolved.
 */
public final class TeaFileStore{
    private final TreeMap<String, byte[]> files = new TreeMap<String, byte[]>();
    private final TreeMap<String, Long> modified = new TreeMap<String, Long>();
    private final TreeSet<String> directories = new TreeSet<String>();
    private final ArrayList<Change> journal = new ArrayList<Change>();
    /** Known files that are fetched on first read: path to byte length. */
    private final TreeMap<String, Long> lazy = new TreeMap<String, Long>();
    private LazySource lazySource;
    private long nextSequence = 1L;
    private long lastStamp;

    public TeaFileStore(){
        directories.add("");
    }

    /** Supplies the contents of a lazily registered file on its first read. */
    public interface LazySource{
        /** @return the file's bytes, or null if they could not be fetched */
        byte[] fetch(String path);
    }

    /** Types of mutations emitted in journal order. */
    public enum ChangeType{
        /** Create a directory. Parent directories are emitted first. */
        MKDIR,
        /** Create or replace a file. The change contains its complete contents. */
        WRITE,
        /** Recursively delete the file or directory at this path. */
        DELETE
    }

    /** An immutable journal entry. Byte contents are always copied defensively. */
    public static final class Change{
        private final long sequence;
        private final ChangeType type;
        private final String path;
        private final byte[] data;
        private final long modified;

        private Change(long sequence, ChangeType type, String path, byte[] data, long modified){
            this.sequence = sequence;
            this.type = type;
            this.path = path;
            this.data = copy(data);
            this.modified = modified;
        }

        public long getSequence(){
            return sequence;
        }

        public ChangeType getType(){
            return type;
        }

        public String getPath(){
            return path;
        }

        /**
         * Returns the complete file contents for {@link ChangeType#WRITE}, or
         * {@code null} for directory and deletion entries.
         */
        public byte[] getData(){
            return copy(data);
        }

        /** Returns the file's modification time in epoch milliseconds for {@link ChangeType#WRITE}. */
        public long getModified(){
            return modified;
        }

        @Override
        public String toString(){
            return "Change{" + sequence + ", " + type + ", '" + path + "'}";
        }
    }

    /**
     * Normalizes a safe relative POSIX path.
     *
     * @throws IllegalArgumentException if the path is null, absolute, contains
     * a backslash or NUL, uses a Windows drive prefix, or contains traversal
     */
    public static String normalize(String path){
        if(path == null) throw new IllegalArgumentException("Path cannot be null.");
        if(path.indexOf('\0') >= 0) throw unsafe(path, "NUL characters are not allowed");
        if(path.indexOf('\\') >= 0) throw unsafe(path, "backslashes are not POSIX separators");
        if(path.startsWith("/")) throw unsafe(path, "absolute paths are not allowed");
        if(path.length() >= 2 && isAsciiLetter(path.charAt(0)) && path.charAt(1) == ':'){
            throw unsafe(path, "Windows drive paths are not allowed");
        }

        StringBuilder normalized = new StringBuilder(path.length());
        int segmentStart = 0;
        for(int i = 0; i <= path.length(); i++){
            if(i != path.length() && path.charAt(i) != '/') continue;

            String segment = path.substring(segmentStart, i);
            segmentStart = i + 1;
            if(segment.length() == 0 || segment.equals(".")) continue;
            if(segment.equals("..")) throw unsafe(path, "parent traversal is not allowed");

            if(normalized.length() > 0) normalized.append('/');
            normalized.append(segment);
        }
        return normalized.toString();
    }

    /**
     * Loads an initial file without marking it dirty. Parent directories are
     * inferred. This is intended for startup hydration before normal access.
     */
    public synchronized void preload(String path, byte[] data){
        preload(path, data, 0L);
    }

    /**
     * Loads an initial file, taking ownership of {@code data} instead of copying it.
     * The caller must not modify the array afterwards. Used for large packaged assets.
     */
    public synchronized void preloadOwned(String path, byte[] data){
        String normalized = requireFilePath(path);
        requireData(data);
        if(directories.contains(normalized)){
            throw new IllegalStateException("Cannot preload a file over a directory: " + normalized);
        }
        ensureParentsCanBeDirectories(normalized);
        createDirectories(parent(normalized), false);
        lazy.remove(normalized);
        files.put(normalized, data);
        modified.put(normalized, 0L);
    }

    /** Loads an initial file with a known modification time, in epoch milliseconds. */
    public synchronized void preload(String path, byte[] data, long modifiedMillis){
        String normalized = requireFilePath(path);
        requireData(data);
        if(directories.contains(normalized)){
            throw new IllegalStateException("Cannot preload a file over a directory: " + normalized);
        }
        ensureParentsCanBeDirectories(normalized);
        createDirectories(parent(normalized), false);
        files.put(normalized, copy(data));
        modified.put(normalized, modifiedMillis);
    }

    /**
     * Registers a file that exists but whose bytes are fetched from
     * {@link #setLazySource} the first time it is read. Listing, existence and
     * length work without fetching. Used for rarely-read packaged assets.
     */
    public synchronized void preloadLazy(String path, long length){
        String normalized = requireFilePath(path);
        if(directories.contains(normalized) || files.containsKey(normalized)) return;
        ensureParentsCanBeDirectories(normalized);
        createDirectories(parent(normalized), false);
        lazy.put(normalized, length);
    }

    public synchronized void setLazySource(LazySource source){
        lazySource = source;
    }

    /** Loads an initial (possibly empty) directory without marking it dirty. */
    public synchronized void preloadDirectory(String path){
        String normalized = normalize(path);
        ensureDirectoryCanBeCreated(normalized);
        createDirectories(normalized, false);
    }

    /**
     * Returns a defensive copy of a file's bytes.
     *
     * @throws IllegalStateException if the path is absent or is a directory
     */
    public synchronized byte[] read(String path){
        String normalized = normalize(path);
        byte[] data = files.get(normalized);
        if(data == null && lazy.containsKey(normalized)) data = fetchLazy(normalized);
        if(data != null) return copy(data);
        if(directories.contains(normalized)){
            throw new IllegalStateException("Cannot read a directory: " + display(normalized));
        }
        throw new IllegalStateException("File does not exist: " + display(normalized));
    }

    /** Creates or replaces a file, creating missing parent directories. */
    public synchronized void write(String path, byte[] data){
        String normalized = requireFilePath(path);
        requireData(data);
        if(directories.contains(normalized)){
            throw new IllegalStateException("Cannot write a file over a directory: " + normalized);
        }
        ensureParentsCanBeDirectories(normalized);
        createDirectories(parent(normalized), true);

        byte[] stored = copy(data);
        lazy.remove(normalized);
        files.put(normalized, stored);
        record(ChangeType.WRITE, normalized, stored, touch(normalized));
    }

    /** Appends bytes to a file, or creates it when it does not yet exist. */
    public synchronized void append(String path, byte[] data){
        String normalized = requireFilePath(path);
        requireData(data);
        if(directories.contains(normalized)){
            throw new IllegalStateException("Cannot append to a directory: " + normalized);
        }

        byte[] old = files.get(normalized);
        if(old == null){
            write(normalized, data);
            return;
        }
        if(data.length > Integer.MAX_VALUE - old.length){
            throw new IllegalStateException("File is too large to append: " + normalized);
        }

        byte[] combined = new byte[old.length + data.length];
        System.arraycopy(old, 0, combined, 0, old.length);
        System.arraycopy(data, 0, combined, old.length, data.length);
        files.put(normalized, combined);
        record(ChangeType.WRITE, normalized, combined, touch(normalized));
    }

    public synchronized boolean exists(String path){
        String normalized = normalize(path);
        return files.containsKey(normalized) || directories.contains(normalized) || lazy.containsKey(normalized);
    }

    public synchronized boolean isDirectory(String path){
        return directories.contains(normalize(path));
    }

    /**
     * Returns a file's modification time in epoch milliseconds, or zero for
     * directories, missing paths and files preloaded without a timestamp.
     */
    public synchronized long lastModified(String path){
        Long value = modified.get(normalize(path));
        return value == null ? 0L : value;
    }

    /** Returns a file's byte length, or zero for directories and missing paths. */
    public synchronized long length(String path){
        String normalized = normalize(path);
        byte[] data = files.get(normalized);
        if(data != null) return data.length;
        Long pending = lazy.get(normalized);
        return pending == null ? 0L : pending;
    }

    /**
     * Returns the immediate child names of a directory in lexical order.
     * Missing paths and files return an empty immutable list.
     */
    public synchronized List<String> list(String path){
        String normalized = normalize(path);
        if(!directories.contains(normalized)) return Collections.emptyList();

        String prefix = normalized.length() == 0 ? "" : normalized + "/";
        TreeSet<String> children = new TreeSet<String>();
        for(String directory : directories){
            addImmediateChild(children, prefix, directory);
        }
        for(String file : files.keySet()){
            addImmediateChild(children, prefix, file);
        }
        for(String file : lazy.keySet()){
            addImmediateChild(children, prefix, file);
        }
        return Collections.unmodifiableList(new ArrayList<String>(children));
    }

    /** Creates this directory and all missing parents. */
    public synchronized boolean mkdirs(String path){
        String normalized = normalize(path);
        ensureDirectoryCanBeCreated(normalized);
        return createDirectories(normalized, true);
    }

    /**
     * Deletes a file or a directory tree. The root itself cannot be deleted.
     *
     * @return whether anything existed at the path
     */
    public synchronized boolean delete(String path){
        String normalized = requireMutablePath(path);
        if(lazy.remove(normalized) != null && !files.containsKey(normalized)){
            record(ChangeType.DELETE, normalized, null, 0L);
            return true;
        }
        if(files.remove(normalized) != null){
            modified.remove(normalized);
            record(ChangeType.DELETE, normalized, null, 0L);
            return true;
        }
        if(!directories.contains(normalized)) return false;

        String prefix = normalized + "/";
        removeWithPrefix(files, prefix);
        removeWithPrefix(modified, prefix);
        removeWithPrefix(directories, prefix);
        directories.remove(normalized);
        record(ChangeType.DELETE, normalized, null, 0L);
        return true;
    }

    /**
     * Moves a file or directory tree to an exact destination path.
     * File destinations may be overwritten. Directory destinations must not
     * already exist, and a directory cannot be moved inside itself.
     */
    public synchronized void move(String source, String destination){
        String from = requireMutablePath(source);
        String to = requireMutablePath(destination);
        if(from.equals(to)){
            if(!exists(from)) throw new IllegalStateException("Source does not exist: " + from);
            return;
        }

        boolean sourceIsFile = files.containsKey(from);
        boolean sourceIsDirectory = directories.contains(from);
        if(!sourceIsFile && !sourceIsDirectory){
            throw new IllegalStateException("Source does not exist: " + from);
        }
        if(sourceIsDirectory && isDescendant(to, from)){
            throw new IllegalArgumentException("Cannot move a directory inside itself: " + from + " -> " + to);
        }

        ensureParentsCanBeDirectories(to);
        if(sourceIsFile){
            if(directories.contains(to)){
                throw new IllegalStateException("Cannot move a file over a directory: " + to);
            }
            createDirectories(parent(to), true);
            byte[] data = files.remove(from);
            Long stamp = modified.remove(from);
            long time = stamp == null ? 0L : stamp;
            files.put(to, data);
            modified.put(to, time);
            record(ChangeType.DELETE, from, null, 0L);
            record(ChangeType.WRITE, to, data, time);
            return;
        }

        if(files.containsKey(to) || directories.contains(to)){
            throw new IllegalStateException("Directory destination already exists: " + to);
        }
        createDirectories(parent(to), true);
        moveDirectory(from, to);
    }

    /** Returns an immutable snapshot of all unacknowledged journal entries. */
    public synchronized List<Change> snapshotChanges(){
        return Collections.unmodifiableList(new ArrayList<Change>(journal));
    }

    public synchronized boolean hasPendingChanges(){
        return !journal.isEmpty();
    }

    /**
     * Removes entries through the supplied sequence after an asynchronous flush
     * succeeds. Entries created while that flush was running remain pending.
     */
    public synchronized void acknowledgeChanges(long sequenceInclusive){
        Iterator<Change> iterator = journal.iterator();
        while(iterator.hasNext()){
            if(iterator.next().sequence <= sequenceInclusive){
                iterator.remove();
            }else{
                break;
            }
        }
    }

    /** Returns and clears a journal snapshot. Prefer acknowledgment for async flushes. */
    public synchronized List<Change> drainChanges(){
        List<Change> changes = Collections.unmodifiableList(new ArrayList<Change>(journal));
        journal.clear();
        return changes;
    }

    private byte[] fetchLazy(String path){
        if(lazySource == null) throw new IllegalStateException("No source for lazily loaded file: " + path);
        byte[] fetched = lazySource.fetch(path);
        if(fetched == null) throw new IllegalStateException("Lazily loaded file could not be fetched: " + path);
        lazy.remove(path);
        files.put(path, fetched);
        modified.put(path, 0L);
        return fetched;
    }

    private void moveDirectory(String from, String to){
        String prefix = from + "/";
        TreeSet<String> oldDirectories = new TreeSet<String>();
        TreeMap<String, byte[]> oldFiles = new TreeMap<String, byte[]>();

        for(String directory : directories){
            if(directory.equals(from) || directory.startsWith(prefix)) oldDirectories.add(directory);
        }
        for(Map.Entry<String, byte[]> entry : files.entrySet()){
            if(entry.getKey().startsWith(prefix)) oldFiles.put(entry.getKey(), entry.getValue());
        }

        directories.removeAll(oldDirectories);
        TreeMap<String, Long> oldModified = new TreeMap<String, Long>();
        for(String file : oldFiles.keySet()){
            files.remove(file);
            Long stamp = modified.remove(file);
            oldModified.put(file, stamp == null ? 0L : stamp);
        }

        record(ChangeType.DELETE, from, null, 0L);
        for(String oldDirectory : oldDirectories){
            String moved = to + oldDirectory.substring(from.length());
            directories.add(moved);
            record(ChangeType.MKDIR, moved, null, 0L);
        }
        for(Map.Entry<String, byte[]> entry : oldFiles.entrySet()){
            String moved = to + entry.getKey().substring(from.length());
            long time = oldModified.get(entry.getKey());
            files.put(moved, entry.getValue());
            modified.put(moved, time);
            record(ChangeType.WRITE, moved, entry.getValue(), time);
        }
    }

    private boolean createDirectories(String path, boolean recordChanges){
        if(path.length() == 0) return false;

        boolean changed = false;
        int slash = -1;
        while(true){
            slash = path.indexOf('/', slash + 1);
            String directory = slash == -1 ? path : path.substring(0, slash);
            if(directories.add(directory)){
                changed = true;
                if(recordChanges) record(ChangeType.MKDIR, directory, null, 0L);
            }
            if(slash == -1) return changed;
        }
    }

    private void ensureDirectoryCanBeCreated(String path){
        if(files.containsKey(path)){
            throw new IllegalStateException("Cannot create a directory over a file: " + display(path));
        }
        ensureParentsCanBeDirectories(path);
    }

    private void ensureParentsCanBeDirectories(String path){
        int slash = path.indexOf('/');
        while(slash >= 0){
            String ancestor = path.substring(0, slash);
            if(files.containsKey(ancestor)){
                throw new IllegalStateException("A parent path is a file: " + ancestor);
            }
            slash = path.indexOf('/', slash + 1);
        }
    }

    /**
     * Appends a journal entry. Older writes that this entry makes irrelevant are
     * dropped, so repeated saves of the same file keep one pending copy and a
     * flush never persists a file's intermediate (e.g. truncated) state.
     * Dropping entries that are already being flushed is safe: that flush owns
     * its own copy, and the newer entry stays pending until acknowledged.
     */
    private void record(ChangeType type, String path, byte[] data, long modifiedMillis){
        if(type != ChangeType.MKDIR){
            String prefix = path + "/";
            Iterator<Change> iterator = journal.iterator();
            while(iterator.hasNext()){
                Change old = iterator.next();
                if(old.type == ChangeType.WRITE
                && (old.path.equals(path) || type == ChangeType.DELETE && old.path.startsWith(prefix))){
                    iterator.remove();
                }
            }
        }
        journal.add(new Change(nextSequence++, type, path, data, modifiedMillis));
    }

    /** Returns a strictly increasing wall-clock timestamp for a modified file. */
    private long touch(String path){
        long now = Math.max(System.currentTimeMillis(), lastStamp + 1L);
        lastStamp = now;
        modified.put(path, now);
        return now;
    }

    private static void addImmediateChild(TreeSet<String> children, String prefix, String candidate){
        if(candidate.length() <= prefix.length() || !candidate.startsWith(prefix)) return;
        String remainder = candidate.substring(prefix.length());
        int slash = remainder.indexOf('/');
        children.add(slash < 0 ? remainder : remainder.substring(0, slash));
    }

    private static <T> void removeWithPrefix(Map<String, T> values, String prefix){
        Iterator<String> iterator = values.keySet().iterator();
        while(iterator.hasNext()){
            if(iterator.next().startsWith(prefix)) iterator.remove();
        }
    }

    private static void removeWithPrefix(TreeSet<String> values, String prefix){
        Iterator<String> iterator = values.iterator();
        while(iterator.hasNext()){
            if(iterator.next().startsWith(prefix)) iterator.remove();
        }
    }

    private static boolean isDescendant(String path, String possibleAncestor){
        return path.startsWith(possibleAncestor + "/");
    }

    private static String parent(String path){
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String requireFilePath(String path){
        String normalized = normalize(path);
        if(normalized.length() == 0){
            throw new IllegalArgumentException("A file path cannot address the store root.");
        }
        return normalized;
    }

    private static String requireMutablePath(String path){
        String normalized = normalize(path);
        if(normalized.length() == 0){
            throw new IllegalArgumentException("The store root cannot be moved or deleted.");
        }
        return normalized;
    }

    private static void requireData(byte[] data){
        if(data == null) throw new IllegalArgumentException("File data cannot be null.");
    }

    private static byte[] copy(byte[] data){
        if(data == null) return null;
        byte[] copied = new byte[data.length];
        System.arraycopy(data, 0, copied, 0, data.length);
        return copied;
    }

    private static boolean isAsciiLetter(char value){
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    private static IllegalArgumentException unsafe(String path, String reason){
        return new IllegalArgumentException("Unsafe path '" + path + "': " + reason + ".");
    }

    private static String display(String path){
        return path.length() == 0 ? "<root>" : path;
    }
}
