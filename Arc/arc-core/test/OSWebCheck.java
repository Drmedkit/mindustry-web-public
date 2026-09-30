import arc.util.OS;

/** Dependency-free JVM checks for the explicit browser platform mode. */
public class OSWebCheck{
    public static void main(String[] args){
        boolean windows = OS.isWindows;
        boolean linux = OS.isLinux;
        boolean mac = OS.isMac;
        boolean ios = OS.isIos;
        boolean android = OS.isAndroid;
        boolean arm = OS.isARM;
        boolean bit64 = OS.is64Bit;

        check(!OS.isWeb, "browser mode must remain opt-in");
        OS.setWebRuntime(true);
        check(OS.isWeb, "browser mode must be enabled");
        check(!OS.isWindows && !OS.isLinux && !OS.isMac, "browser mode must not impersonate a desktop OS");
        check(!OS.isIos && !OS.isAndroid, "browser mode must not impersonate a mobile OS");
        check(!OS.isARM && !OS.is64Bit, "browser mode must not expose a native CPU ABI");
        check(OS.getAppDataDirectoryString("mindustry").equals(""), "browser app-data paths must stay virtual");

        OS.setWebRuntime(false);
        check(!OS.isWeb, "browser mode must be reversible for JVM tests and tooling");
        check(OS.isWindows == windows && OS.isLinux == linux && OS.isMac == mac, "desktop flags must be restored");
        check(OS.isIos == ios && OS.isAndroid == android, "mobile flags must be restored");
        check(OS.isARM == arm && OS.is64Bit == bit64, "architecture flags must be restored");

        System.out.println("OSWebCheck passed");
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
