package arc.backend.teavm;

import arc.Settings;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Structs;
import arc.util.Log;
import arc.util.Log.LogLevel;

import java.util.Arrays;

/** End-to-end JVM check for Arc settings stored in the browser-local filesystem. */
public final class TeaSettingsTest{
    private int checks;

    public static void main(String[] args){
        TeaSettingsTest test = new TeaSettingsTest();
        test.settingsRoundTripAndRecoverFromBackup();
        test.backupsSortNewestFirst();
        System.out.println("TeaSettingsTest: " + test.checks + " checks passed");
    }

    private void settingsRoundTripAndRecoverFromBackup(){
        TeaRuntime.configure();

        TeaFiles files = new TeaFiles();
        Fi dataDirectory = files.local("profiles/ground-zero");
        Settings saved = settingsAt(dataDirectory);
        saved.load();
        saved.put("tutorial-complete", true);
        saved.put("wave", 37);
        saved.put("play-time", 9876543210L);
        saved.put("music-volume", 0.625f);
        saved.put("player-name", "browser-player");
        saved.put("campaign-state", bytes(0, 1, 127, 128, 255));
        saved.forceSave();

        Fi primary = saved.getSettingsFile();
        truth(primary instanceof TeaFi, "settings must use a TeaFi local handle");
        truth(primary.exists(), "forceSave must create the local settings file");
        truth(primary.length() > 0, "settings file must contain serialized values");

        Fi[] rollingBackups = saved.getBackupFolder().list(".bin");
        equal(1, rollingBackups.length);
        array(primary.readBytes(), rollingBackups[0].readBytes());

        Settings reloaded = settingsAt(dataDirectory);
        reloaded.load();
        assertValues(reloaded);

        Fi stableBackup = reloaded.getBackupSettingsFile();
        truth(stableBackup.exists(), "a successful load must refresh settings_backup.bin");
        array(primary.readBytes(), stableBackup.readBytes());

        primary.writeBytes(bytes(0, 0, 0, 0));
        Settings recovered = settingsAt(dataDirectory);
        LogLevel previousLevel = Log.level;
        try{
            // Recovery deliberately logs the rejected primary file; keep the compatibility gate output unambiguous.
            Log.level = LogLevel.none;
            recovered.load();
        }finally{
            Log.level = previousLevel;
        }
        assertValues(recovered);
        array(stableBackup.readBytes(), primary.readBytes());
    }

    /** Settings rotates and recovers backups by lastModified, newest first. */
    private void backupsSortNewestFirst(){
        TeaFiles files = new TeaFiles();
        Fi folder = files.local("rotation/settings_backups");
        folder.child("9000.bin").writeBytes(bytes(1));
        folder.child("1000.bin").writeBytes(bytes(2));
        folder.child("5000.bin").writeBytes(bytes(3));

        Seq<Fi> previous = folder.seq();
        previous.sort(Structs.comparingLong(f -> -f.lastModified()));
        equal("5000.bin", previous.first().name());
        equal("9000.bin", previous.peek().name());
    }

    private Settings settingsAt(Fi dataDirectory){
        Settings settings = new Settings();
        settings.setAutosave(false);
        settings.setDataDirectory(dataDirectory);
        truth(settings.getDataDirectory() == dataDirectory, "Settings must retain the supplied local data directory");
        return settings;
    }

    private void assertValues(Settings settings){
        truth(settings.getBool("tutorial-complete", false), "boolean value must survive reload");
        equal(37, settings.getInt("wave", -1));
        equal(9876543210L, settings.getLong("play-time", -1L));
        equal(Float.floatToIntBits(0.625f), Float.floatToIntBits(settings.getFloat("music-volume", -1f)));
        equal("browser-player", settings.getString("player-name", null));
        array(bytes(0, 1, 127, 128, 255), settings.getBytes("campaign-state", null));
    }

    private static byte[] bytes(int... values){
        byte[] result = new byte[values.length];
        for(int i = 0; i < values.length; i++) result[i] = (byte)values[i];
        return result;
    }

    private void truth(boolean condition, String message){
        checks++;
        if(!condition) throw new AssertionError(message);
    }

    private void equal(long expected, long actual){
        checks++;
        if(expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private void equal(String expected, String actual){
        checks++;
        if(expected == null ? actual != null : !expected.equals(actual)){
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private void array(byte[] expected, byte[] actual){
        checks++;
        if(!Arrays.equals(expected, actual)){
            throw new AssertionError("Expected " + Arrays.toString(expected) + ", got " + Arrays.toString(actual));
        }
    }
}
