import arc.*;
import arc.audio.*;
import arc.files.*;

/** Dependency-free checks for Arc's silent audio fallback. */
public class AudioFallbackCheck{
    public static void main(String[] args) throws Exception{
        Audio previous = Core.audio;
        Audio.setJavaFallback(true);

        try{
            Audio audio = new Audio();
            Core.audio = audio;

            check(Audio.isJavaFallback(), "fallback must be enabled");
            check(!audio.initialized(), "silent audio must not report SoLoud as initialized");
            check(!audio.soundBus.valid() && !audio.musicBus.valid(), "silent buses must have no native handles");

            Filters.BiquadFilter biquad = new Filters.BiquadFilter();
            Filters.EchoFilter echo = new Filters.EchoFilter();
            Filters.LofiFilter lofi = new Filters.LofiFilter();
            Filters.FlangerFilter flanger = new Filters.FlangerFilter();
            Filters.WaveShaperFilter waveShaper = new Filters.WaveShaperFilter();
            Filters.BassBoostFilter bassBoost = new Filters.BassBoostFilter();
            Filters.RobotizeFilter robotize = new Filters.RobotizeFilter();
            Filters.FreeverbFilter freeverb = new Filters.FreeverbFilter();

            biquad.set(0, 500f, 1f);
            echo.set(0.2f, 0.5f, 0.1f);
            lofi.set(8000f, 8f);
            flanger.set(0.01f, 2f);
            waveShaper.set(0.5f);
            bassBoost.set(1f);
            robotize.set(30f, 0);
            freeverb.set(0f, 0.5f, 0.5f, 1f);

            audio.soundBus.setFilter(0, biquad);
            audio.soundBus.setFilterParam(0, Filters.paramWet, 1f);
            audio.soundBus.fadeFilterParam(0, Filters.paramWet, 0f, 0.25f);
            audio.soundBus.setVolume(0.5f);
            audio.soundBus.play();
            audio.soundBus.stop();
            check(!audio.soundBus.playing(), "silent bus must never play");

            Fi missing = new Fi("/this/file/does/not/exist.wav");
            Sound sound = new Sound(missing);
            Sound stream = Sound.createStream(missing);
            sound.load(new byte[]{1, 2, 3}, false);
            stream.load(new byte[]{1, 2, 3}, true);
            exerciseSource(sound, biquad);
            exerciseSource(stream, echo);
            check(sound.play() == -1, "silent sound play must fail without a voice");
            check(sound.loop() == -1, "silent sound loop must fail without a voice");
            check(sound.getLength() == 0f, "silent sound length must be zero");

            Music music = new Music(missing);
            Music externalMusic = Music.create(missing);
            music.load(new byte[]{1, 2, 3});
            exerciseMusic(music, lofi);
            exerciseMusic(externalMusic, flanger);

            check(audio.newSound(missing).play() == -1, "Audio.newSound must return a silent source");
            check(!audio.newMusic(missing).isPlaying(), "Audio.newMusic must return silent music");
            check(audio.play(sound, 1f, 1f, 0f, false) == -1, "Audio.play must be a no-op");
            check(!audio.isPlaying(1), "silent audio has no valid voices");
            check(audio.countPlaying(sound) == 0 && audio.countTotalPlaying() == 0, "silent play counts must be zero");

            audio.protect(1, true);
            audio.stop(sound);
            audio.stop(1);
            audio.setPaused(1, true);
            audio.setLooping(1, true);
            audio.setPitch(1, 2f);
            audio.setVolume(1, 0.5f);
            audio.set(1, 0f, 0.5f);
            audio.setFilter(0, freeverb);
            audio.setFilterParam(1, 0, 0, 1f);
            audio.fadeFilterParam(1, 0, 0, 1f, 1f);

            AudioBus detached = new AudioBus();
            Core.audio = null;
            detached.play();
            detached.stop();
            detached.setFilter(0, bassBoost);
            detached.setFilterParam(0, 0, 1f);
            detached.fadeFilterParam(0, 0, 1f, 1f);
            detached.setVolume(1f);
            check(!detached.playing(), "detached silent bus must be safe");

            Core.audio = audio;
            sound.dispose();
            stream.dispose();
            music.dispose();
            externalMusic.dispose();
            detached.dispose();
            audio.dispose();
            check(!audio.initialized(), "disposed silent audio must remain uninitialized");
        }finally{
            Core.audio = previous;
            Audio.setJavaFallback(false);
        }

        check(!Audio.isJavaFallback(), "native audio must remain the default");
        System.out.println("AudioFallbackCheck passed");
    }

    private static void exerciseSource(AudioSource source, AudioFilter filter){
        source.setFilter(filter);
        source.setFilter(1, null);
        source.setPriority(2f);
        source.setMaxConcurrent(3);
        source.setConcurrentGroup(4);
        source.setMinConcurrentInterrupt(0.1f);
        source.setMinConcurrentInterruptFraction(0.1f, 0.5f);
        source.setSingleInstance(true);
        source.stop();
        check(!source.valid(), "silent source must have no native handle");
        check(source.countPlaying() == 0, "silent source must have no voices");
    }

    private static void exerciseMusic(Music music, AudioFilter filter){
        exerciseSource(music, filter);
        music.play();
        music.pause(true);
        music.stop();
        music.setLooping(true);
        music.setVolume(0.5f);
        music.set(0.25f, 0.75f);
        music.setPosition(2f);
        check(!music.isPlaying(), "silent music must never play");
        check(music.getPosition() == 0f, "silent music position must be zero");
        check(music.getLength() == 0f, "silent music length must be zero");
    }

    private static void check(boolean value, String message){
        if(!value) throw new AssertionError(message);
    }
}
