package mindustry.core;

import arc.*;
import arc.files.*;
import arc.math.*;
import arc.scene.*;
import arc.struct.*;
import arc.util.serialization.*;
import mindustry.mod.*;
import mindustry.net.*;
import mindustry.net.Net.*;
import mindustry.type.*;
import mindustry.ui.FileChooser.*;
import rhino.*;

public interface Platform{

    /** Dynamically creates a class loader for a jar file. This loader must be child-first. */
    default ClassLoader loadJar(Fi jar, ClassLoader parent) throws Exception{
        throw new UnsupportedOperationException("JVM class mods are unavailable on this platform.");
    }

    /** Steam: Update lobby visibility.*/
    default void updateLobby(){}

    /** Steam: Show multiplayer friend invite dialog.*/
    default void inviteFriends(){}

    /** Steam: Share a map on the workshop.*/
    default void publish(Publishable pub){}

    /** Steam: View a listing on the workshop.*/
    default void viewListing(Publishable pub){}

    /** Steam: View a listing on the workshop by an ID.*/
    default void viewListingID(String mapid){}

    /** Steam: Return external workshop maps to be loaded.*/
    default Seq<Fi> getWorkshopContent(Class<? extends Publishable> type){
        return new Seq<>(0);
    }

    /** Steam: Open workshop for maps.*/
    default void openWorkshop(){}

    /** Get the networking implementation.*/
    default NetProvider getNet(){
        return new OfflineNetProvider();
    }

    /** Gets the scripting implementation. */
    default Scripts createScripts(){
        throw new UnsupportedOperationException("Rhino scripting is unavailable on this platform.");
    }

    default Context getScriptContext(){
        throw new UnsupportedOperationException("Rhino scripting is unavailable on this platform.");
    }

    /** Update discord RPC. */
    default void updateRPC(){
    }

    /** Initializes desktop/server self-update support. Browser and store builds leave this disabled. */
    default void initializeUpdateService(BeControl control){
    }

    /** Adds a platform-specific self-update action to the main menu. */
    default void addSelfUpdateButton(Group parent){
    }

    /** Must be a base64 string 8 bytes in length. */
    default String getUUID(){
        String uuid = Core.settings.getString("uuid", "");
        if(uuid.isEmpty()){
            byte[] result = new byte[8];
            new Rand().nextBytes(result);
            uuid = new String(Base64Coder.encode(result));
            Core.settings.put("uuid", uuid);
            return uuid;
        }
        return uuid;
    }

    /** Only used for iOS: open the share menu for a map or save. */
    default void shareFile(Fi file){
    }

    /** Do not call directly; use the builder pattern in {@link mindustry.ui.FileChooser}. */
    default void showFileChooser(FileChooserParams params){
        throw new IllegalArgumentException("Not implemented on this platform!");
    }

    /** Hide the app. Android only. */
    default void hide(){
    }

    /** Forces the app into landscape mode.*/
    default void beginForceLandscape(){
    }

    /** Stops forcing the app into landscape orientation.*/
    default void endForceLandscape(){
    }

    interface FileWriter{
        void write(Fi file) throws Throwable;
    }
}
