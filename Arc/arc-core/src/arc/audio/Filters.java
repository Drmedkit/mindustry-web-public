package arc.audio;

import static arc.audio.Soloud.*;

public class Filters{
    public static final int paramWet = 0;

    public static class BiquadFilter extends AudioFilter{

        public BiquadFilter(){
            super(Audio.isJavaFallback() ? 0 : filterBiquad());
        }

        public void set(int type, float frequency, float resonance){
            if(Audio.isJavaFallback() || handle == 0) return;
            biquadSet(handle, type, frequency, resonance);
        }
    }

    public static class EchoFilter extends AudioFilter{

        public EchoFilter(){
            super(Audio.isJavaFallback() ? 0 : filterEcho());
        }

        public void set(float delay, float decay, float filter){
            if(Audio.isJavaFallback() || handle == 0) return;
            echoSet(handle, delay, decay, filter);
        }
    }

    public static class LofiFilter extends AudioFilter{

        public LofiFilter(){
            super(Audio.isJavaFallback() ? 0 : filterLofi());
        }

        public void set(float sampleRate, float depth){
            if(Audio.isJavaFallback() || handle == 0) return;
            lofiSet(handle, sampleRate, depth);
        }
    }

    public static class FlangerFilter extends AudioFilter{

        public FlangerFilter(){
            super(Audio.isJavaFallback() ? 0 : filterFlanger());
        }

        public void set(float delay, float frequency){
            if(Audio.isJavaFallback() || handle == 0) return;
            flangerSet(handle, delay, frequency);
        }
    }

    public static class WaveShaperFilter extends AudioFilter{

        public WaveShaperFilter(){
            super(Audio.isJavaFallback() ? 0 : filterWaveShaper());
        }

        public void set(float amount){
            if(Audio.isJavaFallback() || handle == 0) return;
            waveShaperSet(handle, amount);
        }
    }

    public static class BassBoostFilter extends AudioFilter{

        public BassBoostFilter(){
            super(Audio.isJavaFallback() ? 0 : filterBassBoost());
        }

        public void set(float amount){
            if(Audio.isJavaFallback() || handle == 0) return;
            bassBoostSet(handle, amount);
        }
    }

    public static class RobotizeFilter extends AudioFilter{

        public RobotizeFilter(){
            super(Audio.isJavaFallback() ? 0 : filterRobotize());
        }

        public void set(float freq, int waveform){
            if(Audio.isJavaFallback() || handle == 0) return;
            robotizeSet(handle, freq, waveform);
        }
    }

    public static class FreeverbFilter extends AudioFilter{

        public FreeverbFilter(){
            super(Audio.isJavaFallback() ? 0 : filterFreeverb());
        }

        public void set(float mode, float roomSize, float damp, float width){
            if(Audio.isJavaFallback() || handle == 0) return;
            freeverbSet(handle, mode, roomSize, damp, width);
        }
    }
}
