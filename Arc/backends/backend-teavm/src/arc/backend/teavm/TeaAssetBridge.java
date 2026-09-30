package arc.backend.teavm;

import arc.graphics.Pixmap;
import org.teavm.jso.JSBody;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSString;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Transfers assets fetched by the JavaScript bootloader into Arc's synchronous VFS.
 * <p>
 * Rarely read assets (music while audio is silent, other languages, the
 * fallback atlas) are not preloaded. They are registered with their size and
 * fetched synchronously the first time the game reads them.
 */
public final class TeaAssetBridge{
    private TeaAssetBridge(){
    }

    public static int populate(TeaFileStore store){
        JSArray<JSString> paths = assetPaths();
        for(int i = 0; i < paths.getLength(); i++){
            String path = paths.get(i).stringValue();
            Int8Array bytes = assetBytes(path);
            if(bytes == null) throw new IllegalStateException("Preloaded asset is missing: " + path);
            // copyToJavaArray already made a private copy; the store can own it.
            store.preloadOwned(path, bytes.copyToJavaArray());
            // Java owns a copy now; release the page's copy to halve asset memory.
            releaseAsset(path);
        }

        JSArray<JSString> lazyPaths = lazyAssetPaths();
        for(int i = 0; i < lazyPaths.getLength(); i++){
            String path = lazyPaths.get(i).stringValue();
            store.preloadLazy(path, (long)lazyAssetLength(path));
        }
        store.setLazySource(path -> {
            Int8Array bytes = fetchLazyAsset(path);
            return bytes == null ? null : bytes.copyToJavaArray();
        });

        // Large PNGs were decoded by the browser while downloading (png-decoder.js).
        Pixmap.pixelSource = (path, size) -> {
            if(!hasDecodedPixels(path)) return null;
            size[0] = decodedWidth(path);
            size[1] = decodedHeight(path);
            return java.nio.ByteBuffer.wrap(decodedPixels(path).copyToJavaArray());
        };
        return paths.getLength();
    }

    /** Drops the page's decoded pixels once loading is done (textures are on the GPU by then). */
    public static void releaseDecodedPixels(){
        Pixmap.pixelSource = null;
        clearDecodedPixels();
    }

    @JSBody(params = {"path"}, script = "var d = globalThis.__mindustryDecoded; return !!(d && d[path]);")
    private static native boolean hasDecodedPixels(String path);

    @JSBody(params = {"path"}, script = "return globalThis.__mindustryDecoded[path].width;")
    private static native int decodedWidth(String path);

    @JSBody(params = {"path"}, script = "return globalThis.__mindustryDecoded[path].height;")
    private static native int decodedHeight(String path);

    @JSBody(params = {"path"}, script =
        "var rgba = globalThis.__mindustryDecoded[path].rgba; return new Int8Array(rgba.buffer, rgba.byteOffset, rgba.byteLength);")
    private static native Int8Array decodedPixels(String path);

    @JSBody(script = "globalThis.__mindustryDecoded = null;")
    private static native void clearDecodedPixels();

    @JSBody(script = "return Object.keys(globalThis.__mindustryAssets || {});")
    private static native JSArray<JSString> assetPaths();

    @JSBody(params = {"path"}, script = "return globalThis.__mindustryAssets[path] || null;")
    private static native Int8Array assetBytes(String path);

    @JSBody(params = {"path"}, script = "delete globalThis.__mindustryAssets[path];")
    private static native void releaseAsset(String path);

    @JSBody(script = "return Object.keys(globalThis.__mindustryLazyAssets || {});")
    private static native JSArray<JSString> lazyAssetPaths();

    @JSBody(params = {"path"}, script = "return globalThis.__mindustryLazyAssets[path].bytes;")
    private static native double lazyAssetLength(String path);

    /**
     * Blocking fetch. Arc's file API is synchronous, so a rarely-read asset is
     * downloaded with a synchronous request the moment it is needed.
     */
    @JSBody(params = {"path"}, script =
        "var entry = globalThis.__mindustryLazyAssets && globalThis.__mindustryLazyAssets[path];"
        + "if(!entry) return null;"
        + "try {"
        + "  var request = new XMLHttpRequest();"
        + "  request.open('GET', entry.url, false);"
        + "  request.overrideMimeType('text/plain; charset=x-user-defined');"
        + "  request.send();"
        + "  if(request.status < 200 || request.status >= 300) { console.warn('Asset ' + path + ' returned HTTP ' + request.status); return null; }"
        + "  var text = request.responseText, bytes = new Int8Array(text.length);"
        + "  for(var i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i) & 255;"
        + "  delete globalThis.__mindustryLazyAssets[path];"
        + "  return bytes;"
        + "} catch(e) { console.warn('Asset ' + path + ' could not be fetched', e); return null; }")
    private static native Int8Array fetchLazyAsset(String path);
}
