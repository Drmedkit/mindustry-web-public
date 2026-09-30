package arc.backend.teavm;

import arc.Files;
import arc.files.Fi;
import arc.util.ArcRuntimeException;

/**
 * Arc filesystem facade for the browser backend.
 * <p>
 * Packaged {@link FileType#internal internal} and
 * {@link FileType#classpath classpath} assets share one read-only store.
 * {@link FileType#local Local} data uses a separate writable store so a save
 * can never replace a packaged asset with the same path. The browser has no
 * meaningful absolute or external filesystem, so those handle types are
 * rejected at creation time.
 */
public final class TeaFiles implements Files{
    public static final String internalPath = "internal:/";
    public static final String localPath = "local:/";

    private final TeaFileStore assets;
    private final TeaFileStore local;

    public TeaFiles(){
        this(new TeaFileStore(), new TeaFileStore());
    }

    public TeaFiles(TeaFileStore assets, TeaFileStore local){
        if(assets == null) throw new IllegalArgumentException("Asset store cannot be null.");
        if(local == null) throw new IllegalArgumentException("Local store cannot be null.");
        if(assets == local){
            throw new IllegalArgumentException("Packaged assets and writable local data must use separate stores.");
        }
        this.assets = assets;
        this.local = local;
    }

    @Override
    public Fi get(String path, FileType type){
        if(type == null) throw new IllegalArgumentException("File type cannot be null.");
        switch(type){
            case internal:
            case classpath:
                return new TeaFi(assets, path, type);
            case local:
                return new TeaFi(local, path, type);
            case absolute:
            case external:
                throw unsupported(type, path);
            default:
                throw new ArcRuntimeException("Unsupported browser file type: " + type);
        }
    }

    /** Uses the browser-local cache namespace instead of an absolute host path. */
    @Override
    public Fi cache(String path){
        return local("cache").child(path);
    }

    @Override
    public String getCachePath(){
        return localPath + "cache";
    }

    @Override
    public String getInternalStoragePath(){
        return internalPath;
    }

    @Override
    public String getExternalStoragePath(){
        throw new ArcRuntimeException("External storage is not available in a browser.");
    }

    @Override
    public boolean isExternalStorageAvailable(){
        return false;
    }

    @Override
    public String getLocalStoragePath(){
        return localPath;
    }

    @Override
    public boolean isLocalStorageAvailable(){
        return true;
    }

    /** Store hydrated from the packaged asset manifest before startup. */
    public TeaFileStore getAssetStore(){
        return assets;
    }

    /** Store hydrated from, and eventually flushed to, browser persistence. */
    public TeaFileStore getLocalStore(){
        return local;
    }

    private static ArcRuntimeException unsupported(FileType type, String path){
        return new ArcRuntimeException("Browser filesystem does not support " + type + " paths: " + path);
    }
}
