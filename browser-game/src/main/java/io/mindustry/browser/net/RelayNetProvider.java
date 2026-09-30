package io.mindustry.browser.net;

import arc.Core;
import arc.backend.teavm.TeaWebSocket;
import arc.func.Cons;
import arc.func.Prov;
import arc.struct.IntMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Time;
import io.mindustry.browser.net.RelayCodec.Outbox;
import mindustry.Vars;
import mindustry.net.Host;
import mindustry.net.Net.NetProvider;
import mindustry.net.NetConnection;
import mindustry.net.Packet;
import mindustry.net.Packets.Connect;
import mindustry.net.Packets.Disconnect;
import org.teavm.jso.JSBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Co-op networking through a Cloudflare Durable Object relay.
 * <p>
 * Hosting works like Mindustry's "Host game": this browser runs the server
 * (NetServer) in-process, and the relay forwards its packets to each player.
 * Joining connects to a room by its six-character code instead of an IP.
 * Packets are batched per destination and sent once per frame.
 */
public final class RelayNetProvider implements NetProvider{
    /** Relay envelope operations; see domain-proxy/src/room.js. */
    private static final byte opFrom = 0x01, opJoin = 0x02, opLeave = 0x03, opTo = 0x10, opKick = 0x20;
    /** Above this many queued bytes, unreliable packets (snapshots) are dropped instead of queued. */
    private static final int congestedBytes = 256 * 1024;
    /** The host sends at most this often; snapshots are produced every 200 ms anyway. */
    private static final float hostFlushMillis = 1000f / 30f;
    private static final int maxHostReconnects = 5;

    /** Receives room lifecycle events for the UI. */
    public interface RoomListener{
        void opened(String code);

        void lost(String reason);
    }

    private final String relayUrl;
    private final String build;
    private final RoomListener rooms;
    private final RelayCodec codec = new RelayCodec();

    private @Nullable TeaWebSocket client;
    private final Outbox clientOut = new Outbox();

    private @Nullable TeaWebSocket host;
    private boolean hosting;
    private @Nullable String roomCode, hostToken;
    private int hostReconnects;
    private long lastHostFlush;
    /** Public rooms re-announce themselves well within the lobby's two-minute expiry. */
    private static final long listingIntervalMillis = 60_000L;
    private boolean listed;
    private @Nullable Prov<String> listing;
    private long lastListing;
    private final IntMap<RelayConnection> connections = new IntMap<>();
    private final Seq<RelayConnection> connectionList = new Seq<>();

    /**
     * @param relayUrl WebSocket origin of the relay, e.g. {@code wss://mindustry.example.com}
     * @param build build ID; the relay only pairs players running the same bundle
     */
    public RelayNetProvider(String relayUrl, String build, RoomListener rooms){
        this.relayUrl = relayUrl.endsWith("/") ? relayUrl.substring(0, relayUrl.length() - 1) : relayUrl;
        this.build = build;
        this.rooms = rooms;
    }

    /** Room codes use an unambiguous alphabet; accepts pasted links and lower case. */
    public static @Nullable String normalizeCode(String input){
        if(input == null) return null;
        String text = input.trim().toUpperCase();
        int query = text.indexOf("ROOM=");
        if(query >= 0) text = text.substring(query + 5);
        StringBuilder code = new StringBuilder();
        for(int i = 0; i < text.length() && code.length() < 6; i++){
            char c = text.charAt(i);
            if("23456789ABCDEFGHJKMNPQRSTVWXYZ".indexOf(c) >= 0) code.append(c);
            else if(c != ' ' && c != '-') break;
        }
        return code.length() == 6 ? code.toString() : null;
    }

    public @Nullable String roomCode(){
        return hosting ? roomCode : null;
    }

    public boolean isListed(){
        return hosting && listed;
    }

    /**
     * Lists the room in the public lobby, or removes it.
     *
     * @param listing JSON listing ({@code {"type":"listing","public":true,...}}), re-read on every refresh
     */
    public void setListed(boolean listed, @Nullable Prov<String> listing){
        this.listed = listed;
        this.listing = listing;
        sendListing();
    }

    private void sendListing(){
        TeaWebSocket socket = host;
        if(socket == null || !socket.isOpen()) return;
        lastListing = Time.millis();
        socket.send(listed && listing != null ? listing.get() : "{\"type\":\"listing\",\"public\":false}");
    }

    /** Fetches the public room list as JSON ({@code {"rooms":[{code,name,map,mode,players,max}]}}). */
    public void fetchLobby(Cons<String> done){
        fetchText(relayUrl.replaceFirst("^ws", "http") + "/api/lobby", text -> Core.app.post(() -> done.get(text)));
    }

    //region client

    @Override
    public void connectClient(String address, int port, Runnable success) throws IOException{
        String code = normalizeCode(address);
        if(code == null) throw new IOException("Room codes have six letters and digits, like K7Q2XM.");
        String status = roomStatus(relayUrl.replaceFirst("^ws", "http") + "/api/rooms/" + code);
        if(status == null) throw new IOException("Could not reach the co-op server. Check your internet connection.");
        if(!status.contains("\"open\":true")) throw new IOException("There is no open room with code " + code + ".");

        disconnectClient();
        TeaWebSocket[] socket = new TeaWebSocket[1];
        socket[0] = new TeaWebSocket(relayUrl + "/ws/join/" + code + "?v=" + build, new TeaWebSocket.Listener(){
            @Override
            public void opened(){
                Core.app.post(() -> {
                    if(client != socket[0]) return;
                    Connect connect = new Connect();
                    connect.addressTCP = "room:" + code;
                    Vars.net.handleClientReceived(connect);
                    success.run();
                });
            }

            @Override
            public void binary(byte[] data){
                Core.app.post(() -> {
                    if(client != socket[0]) return;
                    try{
                        codec.read(data, false, Vars.net::handleClientReceived);
                    }catch(Throwable error){
                        Log.err("Could not read packets from the host.", error);
                    }
                });
            }

            @Override
            public void text(String text){
                if(text.contains("host-away")){
                    Core.app.post(() -> Vars.ui.showInfoToast("The host lost connection. Waiting for them to come back...", 6f));
                }
            }

            @Override
            public void closed(int code, String reason){
                Core.app.post(() -> {
                    if(client != socket[0]) return;
                    client = null;
                    Disconnect disconnect = new Disconnect();
                    disconnect.reason = code == 1000 || code == 4000 || code == 4001 ? "closed" : "error";
                    Vars.net.handleClientReceived(disconnect);
                    if(code == 4001) Vars.ui.showInfo("The host removed you from the room.");
                    else if(code == 4000) Vars.ui.showInfo("The host closed the room.");
                });
            }
        });
        client = socket[0];
    }

    @Override
    public void sendClient(Object object, boolean reliable){
        if(client == null || !(object instanceof Packet packet)) return;
        if(!reliable && client.bufferedAmount() > congestedBytes) return;
        codec.write(packet, clientOut);
    }

    @Override
    public void disconnectClient(){
        TeaWebSocket socket = client;
        client = null;
        clientOut.clear();
        if(socket != null) socket.close(1000, "Left the game");
    }

    //endregion
    //region server

    @Override
    public void hostServer(int port){
        closeServer();
        hosting = true;
        hostReconnects = 0;
        openHostSocket(relayUrl + "/ws/host?v=" + build);
    }

    private void openHostSocket(String url){
        TeaWebSocket[] socket = new TeaWebSocket[1];
        socket[0] = new TeaWebSocket(url, new TeaWebSocket.Listener(){
            @Override
            public void opened(){
            }

            @Override
            public void binary(byte[] data){
                Core.app.post(() -> {
                    if(host == socket[0]) receiveFromRelay(data);
                });
            }

            @Override
            public void text(String text){
                Core.app.post(() -> {
                    if(host != socket[0] || !text.contains("\"type\":\"room\"")) return;
                    roomCode = jsonString(text, "code");
                    hostToken = jsonString(text, "token");
                    boolean reconnect = hostReconnects > 0;
                    hostReconnects = 0;
                    if(!reconnect && roomCode != null) rooms.opened(roomCode);
                    if(reconnect && listed) sendListing();
                });
            }

            @Override
            public void closed(int code, String reason){
                Core.app.post(() -> {
                    if(host != socket[0]) return;
                    host = null;
                    if(!hosting) return;
                    // Reconnect to the same room; players stay attached to it for a minute.
                    if(roomCode != null && hostToken != null && hostReconnects < maxHostReconnects){
                        hostReconnects++;
                        Time.runTask(60f * hostReconnects, () -> {
                            if(hosting && host == null){
                                openHostSocket(relayUrl + "/ws/rehost/" + roomCode + "?v=" + build + "&token=" + hostToken);
                            }
                        });
                    }else{
                        hosting = false;
                        rooms.lost(roomCode == null ? "Could not open a co-op room." : "Lost the connection to the co-op room.");
                    }
                });
            }
        });
        host = socket[0];
    }

    private void receiveFromRelay(byte[] data){
        if(data.length < 3) return;
        int id = ((data[1] & 0xff) << 8) | (data[2] & 0xff);
        switch(data[0]){
            case opJoin -> {
                String address = new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
                RelayConnection connection = new RelayConnection(id, address);
                connections.put(id, connection);
                connectionList.add(connection);
                Connect connect = new Connect();
                connect.addressTCP = address;
                Vars.net.handleServerReceived(connection, connect);
            }
            case opLeave -> {
                RelayConnection connection = connections.remove(id);
                if(connection == null) return;
                connectionList.remove(connection);
                connection.open = false;
                Disconnect disconnect = new Disconnect();
                disconnect.reason = "closed";
                Vars.net.handleServerReceived(connection, disconnect);
            }
            case opFrom -> {
                RelayConnection connection = connections.get(id);
                if(connection == null) return;
                // Same flood limit as ArcNet: 300 packets per 3 seconds.
                if(!connection.packetRate.allow(3000, 300)){
                    Log.warn("Removing player @ from the room: packet spam.", connection.address);
                    connection.close();
                    return;
                }
                byte[] batch = new byte[data.length - 3];
                System.arraycopy(data, 3, batch, 0, batch.length);
                try{
                    codec.read(batch, true, packet -> Vars.net.handleServerReceived(connection, packet));
                }catch(Throwable error){
                    Log.err("Could not read packets from player " + connection.address, error);
                    connection.close();
                }
            }
            default -> {}
        }
    }

    @Override
    public Iterable<? extends NetConnection> getConnections(){
        return connectionList;
    }

    @Override
    public void closeServer(){
        hosting = false;
        listed = false;
        listing = null;
        TeaWebSocket socket = host;
        host = null;
        roomCode = null;
        hostToken = null;
        connections.clear();
        connectionList.clear();
        if(socket != null) socket.close(1000, "Room closed");
    }

    //endregion

    /** Sends everything queued this frame. Called once per frame after the game updates. */
    public void flush(){
        TeaWebSocket clientSocket = client;
        if(clientSocket != null && clientSocket.isOpen() && !clientOut.isEmpty()){
            clientSocket.send(clientOut.data, clientOut.size);
            clientOut.clear();
        }

        TeaWebSocket hostSocket = host;
        if(hostSocket == null || !hostSocket.isOpen()) return;
        if(listed && Time.timeSinceMillis(lastListing) > listingIntervalMillis) sendListing();
        if(Time.timeSinceMillis(lastHostFlush) < hostFlushMillis) return;
        lastHostFlush = Time.millis();
        for(RelayConnection connection : connectionList){
            Outbox out = connection.out;
            if(out.isEmpty()) continue;
            byte[] message = new byte[3 + out.size];
            message[0] = opTo;
            message[1] = (byte)(connection.id >> 8);
            message[2] = (byte)connection.id;
            System.arraycopy(out.data, 0, message, 3, out.size);
            hostSocket.send(message, message.length);
            out.clear();
        }
    }

    @Override
    public void discoverServers(Cons<Host> callback, Runnable done){
        // Rooms are private; there is no LAN or public list to discover.
        done.run();
    }

    @Override
    public void pingHost(String address, int port, Cons<Host> valid, Cons<Exception> failed){
        failed.get(new IOException("Rooms are joined with their code."));
    }

    private final class RelayConnection extends NetConnection{
        final int id;
        final Outbox out = new Outbox();
        boolean open = true;

        RelayConnection(int id, String address){
            super(address);
            this.id = id;
        }

        @Override
        public boolean isConnected(){
            return open && hosting;
        }

        @Override
        public void send(Object object, boolean reliable){
            if(!open || !(object instanceof Packet packet)) return;
            TeaWebSocket socket = host;
            if(!reliable && (socket == null || socket.bufferedAmount() > congestedBytes)) return;
            codec.write(packet, out);
        }

        @Override
        public void close(){
            if(!open) return;
            open = false;
            TeaWebSocket socket = host;
            if(socket != null){
                socket.send(new byte[]{opKick, (byte)(id >> 8), (byte)id}, 3);
            }
        }
    }

    /** Reads a string field from the relay's small, fixed JSON messages. */
    private static @Nullable String jsonString(String json, String key){
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if(start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    @org.teavm.jso.JSFunctor
    interface TextCallback extends org.teavm.jso.JSObject{
        void handle(String text);
    }

    @JSBody(params = {"url", "done"}, script =
        "fetch(url, {cache: 'no-store'}).then(function(r){ return r.ok ? r.text() : '{\"rooms\":[]}'; })"
        + ".then(function(t){ done(t); }, function(){ done(null); });")
    private static native void fetchText(String url, TextCallback done);

    /** Synchronous status check so a bad code fails immediately with a clear message. */
    @JSBody(params = {"url"}, script =
        "try { var request = new XMLHttpRequest(); request.open('GET', url, false); request.send();"
        + " return request.status === 200 ? request.responseText : (request.status === 404 ? '{\"open\":false}' : null); }"
        + " catch(e) { return null; }")
    private static native String roomStatus(String url);
}
