package arc.backend.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * A binary WebSocket. Callbacks run on the browser's main thread between
 * frames; listeners should hand work to the game loop (for example with
 * {@code Core.app.post}) rather than touching game state directly.
 */
public final class TeaWebSocket{
    public interface Listener{
        void opened();

        void binary(byte[] data);

        void text(String text);

        void closed(int code, String reason);
    }

    private final JSObject socket;

    public TeaWebSocket(String url, Listener listener){
        socket = open(url, listener::opened, data -> listener.binary(data.copyToJavaArray()), listener::text, listener::closed);
    }

    public boolean isOpen(){
        return readyState(socket) == 1;
    }

    /** Bytes queued by the browser but not yet sent; used to drop stale unreliable data. */
    public int bufferedAmount(){
        return bufferedAmount(socket);
    }

    public void send(byte[] data, int length){
        byte[] copy = new byte[length];
        System.arraycopy(data, 0, copy, 0, length);
        send(socket, Int8Array.copyFromJavaArray(copy));
    }

    public void send(String text){
        sendText(socket, text);
    }

    public void close(int code, String reason){
        close(socket, code, reason);
    }

    @JSFunctor
    interface OpenHandler extends JSObject{
        void handle();
    }

    @JSFunctor
    interface BinaryHandler extends JSObject{
        void handle(Int8Array data);
    }

    @JSFunctor
    interface TextHandler extends JSObject{
        void handle(String text);
    }

    @JSFunctor
    interface CloseHandler extends JSObject{
        void handle(int code, String reason);
    }

    @JSBody(params = {"url", "onOpen", "onBinary", "onText", "onClose"}, script =
        "var socket = new WebSocket(url); socket.binaryType = 'arraybuffer';"
        + "socket.onopen = function(){ onOpen(); };"
        + "socket.onmessage = function(event){ if(typeof event.data === 'string') onText(event.data); else onBinary(new Int8Array(event.data)); };"
        + "socket.onclose = function(event){ onClose(event.code, event.reason || ''); };"
        + "return socket;")
    private static native JSObject open(String url, OpenHandler onOpen, BinaryHandler onBinary, TextHandler onText, CloseHandler onClose);

    @JSBody(params = {"socket"}, script = "return socket.readyState;")
    private static native int readyState(JSObject socket);

    @JSBody(params = {"socket"}, script = "return socket.bufferedAmount;")
    private static native int bufferedAmount(JSObject socket);

    @JSBody(params = {"socket", "data"}, script = "if(socket.readyState === 1) socket.send(data);")
    private static native void send(JSObject socket, Int8Array data);

    @JSBody(params = {"socket", "text"}, script = "if(socket.readyState === 1) socket.send(text);")
    private static native void sendText(JSObject socket, String text);

    @JSBody(params = {"socket", "code", "reason"}, script = "try { socket.close(code, reason); } catch(e) { socket.close(); }")
    private static native void close(JSObject socket, int code, String reason);
}
