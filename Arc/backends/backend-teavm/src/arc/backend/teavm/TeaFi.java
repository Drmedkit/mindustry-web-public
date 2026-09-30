package arc.backend.teavm;

import arc.Files.FileType;
import arc.files.Fi;
import arc.util.ArcRuntimeException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.List;

/** A synchronous Arc file handle backed by a {@link TeaFileStore}. */
public final class TeaFi extends Fi{
    private static final byte[] emptyBytes = new byte[0];

    private final TeaFileStore store;
    private final String virtualPath;

    public TeaFi(TeaFileStore store, String path, FileType type){
        super(path == null ? "" : path, requireSupported(type));
        if(store == null) throw new IllegalArgumentException("File store cannot be null.");
        this.store = store;
        this.virtualPath = TeaFileStore.normalize(path);
        // Fi keeps a File for compatibility with filters. It is only a stable,
        // relative label here and is never used for host filesystem access.
        this.file = new File(virtualPath);
    }

    public TeaFileStore store(){
        return store;
    }

    @Override
    public String path(){
        return virtualPath;
    }

    @Override
    public String absolutePath(){
        return type.name() + ":/" + virtualPath;
    }

    @Override
    public String name(){
        int slash = virtualPath.lastIndexOf('/');
        return slash < 0 ? virtualPath : virtualPath.substring(slash + 1);
    }

    @Override
    public String extension(){
        String name = name();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    @Override
    public String nameWithoutExtension(){
        String name = name();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    @Override
    public String pathWithoutExtension(){
        int dot = virtualPath.lastIndexOf('.');
        return dot < 0 ? virtualPath : virtualPath.substring(0, dot);
    }

    @Override
    public Fi child(String name){
        String child = TeaFileStore.normalize(name);
        if(child.length() == 0) return new TeaFi(store, virtualPath, type);
        return new TeaFi(store, virtualPath.length() == 0 ? child : virtualPath + "/" + child, type);
    }

    @Override
    public Fi sibling(String name){
        if(virtualPath.length() == 0) throw new ArcRuntimeException("Cannot get the sibling of the root.");
        String sibling = TeaFileStore.normalize(name);
        int slash = virtualPath.lastIndexOf('/');
        String parent = slash < 0 ? "" : virtualPath.substring(0, slash);
        return new TeaFi(store, parent.length() == 0 ? sibling : parent + "/" + sibling, type);
    }

    @Override
    public Fi parent(){
        if(virtualPath.length() == 0) return this;
        int slash = virtualPath.lastIndexOf('/');
        return new TeaFi(store, slash < 0 ? "" : virtualPath.substring(0, slash), type);
    }

    @Override
    public InputStream read(){
        try{
            return new ByteArrayInputStream(store.read(virtualPath));
        }catch(RuntimeException ex){
            throw failure("reading", ex);
        }
    }

    @Override
    public byte[] readBytes(){
        try{
            return store.read(virtualPath);
        }catch(RuntimeException ex){
            throw failure("reading", ex);
        }
    }

    @Override
    public int readBytes(byte[] bytes, int offset, int size){
        if(bytes == null) throw new IllegalArgumentException("Destination bytes cannot be null.");
        if(offset < 0 || size < 0 || offset > bytes.length - size){
            throw new IndexOutOfBoundsException("offset=" + offset + ", size=" + size + ", length=" + bytes.length);
        }
        byte[] contents = readBytes();
        int copied = Math.min(size, contents.length);
        System.arraycopy(contents, 0, bytes, offset, copied);
        return copied;
    }

    /** Browser files can be represented as a heap buffer for read-only maps. */
    @Override
    public ByteBuffer map(FileChannel.MapMode mode){
        if(mode != FileChannel.MapMode.READ_ONLY){
            throw new ArcRuntimeException("Browser files only support read-only memory maps: " + this);
        }
        return ByteBuffer.wrap(readBytes()).order(ByteOrder.nativeOrder());
    }

    @Override
    public OutputStream write(boolean append){
        requireWritable("write to");
        if(virtualPath.length() == 0) throw new ArcRuntimeException("Cannot write to the local storage root.");
        try{
            return new StoreOutputStream(store, virtualPath, append);
        }catch(RuntimeException ex){
            throw failure("opening for writing", ex);
        }
    }

    @Override
    public Writer writer(boolean append, String charset){
        OutputStream output = write(append);
        try{
            return charset == null ? new OutputStreamWriter(output) : new OutputStreamWriter(output, charset);
        }catch(UnsupportedEncodingException ex){
            try{
                output.close();
            }catch(IOException ignored){
            }
            throw failure("opening a writer for", ex);
        }
    }

    @Override
    public void writeBytes(byte[] bytes){
        writeBytes(bytes, false);
    }

    @Override
    public void writeBytes(byte[] bytes, boolean append){
        if(bytes == null) throw new IllegalArgumentException("Source bytes cannot be null.");
        writeBytes(bytes, 0, bytes.length, append);
    }

    @Override
    public void writeBytes(byte[] bytes, int offset, int length, boolean append){
        requireWritable("write to");
        if(bytes == null) throw new IllegalArgumentException("Source bytes cannot be null.");
        if(offset < 0 || length < 0 || offset > bytes.length - length){
            throw new IndexOutOfBoundsException("offset=" + offset + ", length=" + length + ", size=" + bytes.length);
        }
        if(virtualPath.length() == 0) throw new ArcRuntimeException("Cannot write to the local storage root.");

        byte[] copied = new byte[length];
        System.arraycopy(bytes, offset, copied, 0, length);
        try{
            if(append){
                store.append(virtualPath, copied);
            }else{
                store.write(virtualPath, copied);
            }
        }catch(RuntimeException ex){
            throw failure("writing", ex);
        }
    }

    @Override
    public Fi[] list(){
        List<String> names = store.list(virtualPath);
        Fi[] children = new Fi[names.size()];
        for(int i = 0; i < children.length; i++) children[i] = child(names.get(i));
        return children;
    }

    @Override
    public Fi[] list(FileFilter filter){
        if(filter == null) throw new IllegalArgumentException("File filter cannot be null.");
        Fi[] children = list();
        int count = 0;
        for(Fi child : children){
            if(filter.accept(child.file())) children[count++] = child;
        }
        return trim(children, count);
    }

    @Override
    public Fi[] list(FilenameFilter filter){
        if(filter == null) throw new IllegalArgumentException("Filename filter cannot be null.");
        Fi[] children = list();
        int count = 0;
        for(Fi child : children){
            if(filter.accept(file, child.name())) children[count++] = child;
        }
        return trim(children, count);
    }

    @Override
    public Fi[] list(String suffix){
        if(suffix == null) throw new IllegalArgumentException("Suffix cannot be null.");
        Fi[] children = list();
        int count = 0;
        for(Fi child : children){
            if(child.name().endsWith(suffix)) children[count++] = child;
        }
        return trim(children, count);
    }

    @Override
    public boolean isDirectory(){
        return store.isDirectory(virtualPath);
    }

    @Override
    public boolean mkdirs(){
        requireWritable("create directories in");
        try{
            return store.mkdirs(virtualPath);
        }catch(RuntimeException ex){
            throw failure("creating directories for", ex);
        }
    }

    @Override
    public boolean exists(){
        return store.exists(virtualPath);
    }

    @Override
    public boolean delete(){
        requireWritable("delete from");
        if(virtualPath.length() == 0) return false;
        if(isDirectory() && list().length != 0) return false;
        try{
            return store.delete(virtualPath);
        }catch(RuntimeException ex){
            throw failure("deleting", ex);
        }
    }

    @Override
    public boolean deleteDirectory(){
        requireWritable("delete from");
        if(virtualPath.length() == 0) return false;
        try{
            return store.delete(virtualPath);
        }catch(RuntimeException ex){
            throw failure("deleting directory", ex);
        }
    }

    @Override
    public void emptyDirectory(boolean preserveTree){
        requireWritable("empty");
        if(!isDirectory()) return;
        for(Fi entry : list()){
            TeaFi child = (TeaFi)entry;
            if(child.isDirectory() && preserveTree){
                child.emptyDirectory(true);
            }else if(child.isDirectory()){
                child.deleteDirectory();
            }else{
                child.delete();
            }
        }
    }

    @Override
    public void copyTo(Fi destination){
        TeaFi dest = writableDestination(destination, "copy");
        if(!exists()) throw new ArcRuntimeException("Source does not exist: " + this);

        TeaFi target = copyTarget(dest);
        if(sameLocation(target)) return;
        rejectRecursiveTarget(target, "copy");

        if(isDirectory()){
            if(dest.exists() && !dest.isDirectory()){
                throw new ArcRuntimeException("Destination exists but is not a directory: " + dest);
            }
            dest.mkdirs();
            copyDirectoryContents(this, target);
        }else{
            target.writeBytes(readBytes(), false);
        }
    }

    @Override
    public void copyFilesTo(Fi destination){
        if(!isDirectory()) throw new ArcRuntimeException("Source folder must be a directory: " + this);
        TeaFi dest = writableDestination(destination, "copy");
        if(dest.exists() && !dest.isDirectory()){
            throw new ArcRuntimeException("Destination folder must be a directory: " + dest);
        }
        if(sameLocation(dest)) return;
        rejectRecursiveTarget(dest, "copy");
        copyDirectoryContents(this, dest);
    }

    @Override
    public void moveTo(Fi destination){
        requireWritable("move");
        TeaFi dest = writableDestination(destination, "move");
        if(!exists()) throw new ArcRuntimeException("Source does not exist: " + this);

        TeaFi target = copyTarget(dest);
        if(sameLocation(target)) return;
        rejectRecursiveTarget(target, "move");

        copyTo(dest);
        if(isDirectory()){
            if(!deleteDirectory()) throw new ArcRuntimeException("Unable to delete moved source directory: " + this);
        }else if(!delete()){
            throw new ArcRuntimeException("Unable to delete moved source file: " + this);
        }
    }

    @Override
    public long length(){
        return store.length(virtualPath);
    }

    /** Settings backup rotation and recovery sort by this, newest first. */
    @Override
    public long lastModified(){
        return store.lastModified(virtualPath);
    }

    @Override
    public int compareTo(Fi other){
        return path().compareTo(other.path());
    }

    @Override
    public String toString(){
        return virtualPath;
    }

    private TeaFi copyTarget(TeaFi destination){
        if(!isDirectory()) return destination.isDirectory() ? (TeaFi)destination.child(name()) : destination;
        return name().length() == 0 ? destination : (TeaFi)destination.child(name());
    }

    private boolean sameLocation(TeaFi other){
        return store == other.store && virtualPath.equals(other.virtualPath);
    }

    private void rejectRecursiveTarget(TeaFi target, String operation){
        if(!isDirectory() || store != target.store) return;
        if(virtualPath.length() == 0 ? target.virtualPath.length() != 0
        : target.virtualPath.startsWith(virtualPath + "/")){
            throw new ArcRuntimeException("Cannot " + operation + " a directory inside itself: " + this + " -> " + target);
        }
    }

    private static void copyDirectoryContents(TeaFi source, TeaFi destination){
        destination.mkdirs();
        for(Fi entry : source.list()){
            TeaFi child = (TeaFi)entry;
            TeaFi target = (TeaFi)destination.child(child.name());
            if(child.isDirectory()){
                copyDirectoryContents(child, target);
            }else{
                target.writeBytes(child.readBytes(), false);
            }
        }
    }

    private TeaFi writableDestination(Fi destination, String operation){
        if(!(destination instanceof TeaFi)){
            String kind = destination == null ? "null" : String.valueOf(destination.type());
            throw new ArcRuntimeException("Cannot " + operation + " a browser file to unsupported destination type: " + kind);
        }
        TeaFi teaDestination = (TeaFi)destination;
        teaDestination.requireWritable(operation + " to");
        return teaDestination;
    }

    private void requireWritable(String action){
        if(type != FileType.local){
            throw new ArcRuntimeException("Cannot " + action + " read-only " + type + " file: " + this);
        }
    }

    private ArcRuntimeException failure(String operation, Throwable cause){
        return new ArcRuntimeException("Error " + operation + " browser file: " + this + " (" + type + ")", cause);
    }

    private static Fi[] trim(Fi[] values, int size){
        if(size == values.length) return values;
        Fi[] result = new Fi[size];
        System.arraycopy(values, 0, result, 0, size);
        return result;
    }

    private static FileType requireSupported(FileType type){
        if(type == null) throw new IllegalArgumentException("File type cannot be null.");
        if(type != FileType.classpath && type != FileType.internal && type != FileType.local){
            throw new ArcRuntimeException("Browser file handles do not support " + type + " paths.");
        }
        return type;
    }

    /** Output stream that synchronously commits flushed bytes to the store. */
    private static final class StoreOutputStream extends OutputStream{
        private final TeaFileStore store;
        private final String path;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private boolean closed;

        StoreOutputStream(TeaFileStore store, String path, boolean append){
            this.store = store;
            this.path = path;
            if(store.isDirectory(path)) throw new IllegalStateException("Cannot write to a directory: " + path);
            if(!append || !store.exists(path)) store.write(path, emptyBytes);
        }

        @Override
        public void write(int value) throws IOException{
            ensureOpen();
            pending.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException{
            ensureOpen();
            pending.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException{
            ensureOpen();
            if(pending.size() == 0) return;
            byte[] data = pending.toByteArray();
            try{
                store.append(path, data);
                pending.reset();
            }catch(RuntimeException ex){
                throw new IOException("Unable to flush browser file: " + path, ex);
            }
        }

        @Override
        public void close() throws IOException{
            if(closed) return;
            try{
                flush();
            }finally{
                closed = true;
            }
        }

        private void ensureOpen() throws IOException{
            if(closed) throw new IOException("Stream is closed.");
        }
    }
}
