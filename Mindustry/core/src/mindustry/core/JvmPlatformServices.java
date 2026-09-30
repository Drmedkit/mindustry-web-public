package mindustry.core;

import arc.files.Fi;
import mindustry.mod.Scripts;
import mindustry.net.ArcNetProvider;
import mindustry.net.Net.NetProvider;
import rhino.Context;

import java.net.URL;
import java.net.URLClassLoader;

/** JVM-only implementations kept out of browser reachability. */
public final class JvmPlatformServices{
    private JvmPlatformServices(){
    }

    public static NetProvider createNet(){
        return new ArcNetProvider();
    }

    public static Scripts createScripts(){
        return new Scripts();
    }

    public static Context getScriptContext(){
        Context context = Context.getCurrentContext();
        if(context == null) context = Context.enter();
        context.setOptimizationLevel(9);
        return context;
    }

    public static ClassLoader loadJar(Fi jar, ClassLoader parent) throws Exception{
        return new URLClassLoader(new URL[]{jar.file().toURI().toURL()}, parent){
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
                Class<?> loadedClass = findLoadedClass(name);
                if(loadedClass == null){
                    try{
                        loadedClass = findClass(name);
                    }catch(ClassNotFoundException error){
                        return parent.loadClass(name);
                    }
                }
                if(resolve) resolveClass(loadedClass);
                return loadedClass;
            }
        };
    }
}
