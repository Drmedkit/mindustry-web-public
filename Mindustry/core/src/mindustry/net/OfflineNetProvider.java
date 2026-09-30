package mindustry.net;

import arc.func.Cons;
import mindustry.net.Net.NetProvider;

import java.io.IOException;
import java.util.Collections;

/** A socket-free provider for runtimes that expose single-player only. */
public final class OfflineNetProvider implements NetProvider{
    private static final String disabledMessage = "Networking is not available in this build.";

    @Override
    public void connectClient(String ip, int port, Runnable success) throws IOException{
        throw new IOException(disabledMessage);
    }

    @Override
    public void sendClient(Object object, boolean reliable){
    }

    @Override
    public void disconnectClient(){
    }

    @Override
    public void discoverServers(Cons<Host> callback, Runnable done){
        done.run();
    }

    @Override
    public void pingHost(String address, int port, Cons<Host> valid, Cons<Exception> failed){
        failed.get(new IOException(disabledMessage));
    }

    @Override
    public void hostServer(int port) throws IOException{
        throw new IOException(disabledMessage);
    }

    @Override
    public Iterable<? extends NetConnection> getConnections(){
        return Collections.emptyList();
    }

    @Override
    public void closeServer(){
    }
}
