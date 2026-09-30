package arc.audio;

import arc.*;
import arc.util.*;

import static arc.audio.Soloud.*;

public class AudioBus extends AudioSource{
    public int id;

    public AudioBus(){
        if(!Audio.isJavaFallback() && Core.audio != null && Core.audio.initialized){
            init();
        }
    }

    @Override
    public void setFilter(int index, @Nullable AudioFilter filter){
        if(Audio.isJavaFallback() || handle == 0) return;
        sourceFilter(handle, index, filter == null ? 0 : filter.handle);
    }

    AudioBus init(){
        if(Audio.isJavaFallback() || handle != 0) return this;
        this.handle = busNew();
        this.id = sourcePlay(handle);
        return this;
    }

    public boolean playing(){
        return !Audio.isJavaFallback() && handle != 0 && Core.audio != null && Core.audio.isPlaying(id);
    }

    public void play(){
        if(Audio.isJavaFallback() || handle == 0 || idValid(id)) return;
        id = sourcePlay(handle);
    }

    @Override
    public void stop(){
        if(!Audio.isJavaFallback() && Core.audio != null) Core.audio.stop(id);
        id = 0;
    }

    @Override
    public float getLength(){
        return 0;
    }

    public void fadeFilterParam(int filter, int attribute, float value, float timeSec){
        if(!Audio.isJavaFallback() && Core.audio != null) Core.audio.fadeFilterParam(id, filter, attribute, value, timeSec);
    }

    public void setFilterParam(int filter, int attribute, float value){
        if(!Audio.isJavaFallback() && Core.audio != null) Core.audio.setFilterParam(id, filter, attribute, value);
    }

    public void setVolume(float volume){
        if(!Audio.isJavaFallback() && Core.audio != null) Core.audio.setVolume(id, volume);
    }
}
