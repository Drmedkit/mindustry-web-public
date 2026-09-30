package mindustry.async;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;

import java.util.concurrent.*;

import static mindustry.Vars.*;

public class AsyncCore{
    //all processes to be executed each frame
    public final Seq<AsyncProcess> processes = Seq.with(
        new PhysicsProcess(),
        avoidance = new AvoidanceProcess()
    );

    //futures to be awaited
    private final Seq<ArcFuture<?>> futures = new Seq<>();

    private ArcExecutorService executor;

    public AsyncCore(){
        Events.on(WorldLoadEvent.class, e -> {
            complete();
            for(AsyncProcess p : processes){
                p.init();
            }
        });

        Events.on(ResetEvent.class, e -> {
            complete();
            for(AsyncProcess p : processes){
                p.reset();
            }
        });
    }

    public void begin(){
        if(state.isPlaying()){
            //sync begin
            for(AsyncProcess p : processes){
                p.begin();
            }

            futures.clear();

            //init executor with size of potentially-modified process list
            if(executor == null){
                //Arc selects a normal fixed pool on desktop and a serial executor in browser mode.
                //The latter executes submitted work before returning, so the existing Future barrier
                //remains valid without creating or blocking a Java thread.
                executor = Threads.executor("AsyncLogic-Thread", Math.max(processes.size, 1));
            }

            //submit all tasks
            for(AsyncProcess p : processes){
                if(p.shouldProcess()){
                    futures.add(executor.submit(p::process));
                }
            }
        }
    }

    public void end(){
        if(state.isPlaying()){
            complete();

            //sync end (flush data)
            for(AsyncProcess p : processes){
                p.end();
            }
        }
    }

    private void complete(){
        //wait for all threads to stop processing
        for(var future : futures){
            try{
                future.get();
            }catch(Throwable t){
                throw new RuntimeException(t);
            }
        }

        //clear processed futures
        futures.clear();
    }
}
