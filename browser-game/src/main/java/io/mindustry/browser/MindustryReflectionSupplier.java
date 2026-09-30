package io.mindustry.browser;

import org.teavm.classlib.ReflectionContext;
import org.teavm.classlib.ReflectionSupplier;
import org.teavm.model.ClassReader;
import org.teavm.model.MethodDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/** Keeps fields and constructors used by Arc JSON in TeaVM's reflection metadata. */
@SuppressWarnings("deprecation")
public final class MindustryReflectionSupplier implements ReflectionSupplier{
    private static boolean included(String name){
        return name.equals("mindustry.game.Rules") || name.startsWith("mindustry.game.Rules$")
        || name.equals("mindustry.game.GameStats")
        || name.equals("mindustry.game.CampaignRules") || name.equals("mindustry.game.CampaignStats")
        || name.equals("mindustry.game.SectorInfo") || name.startsWith("mindustry.game.SectorInfo$")
        || name.equals("mindustry.game.SpawnGroup")
        || name.equals("mindustry.game.MapObjectives") || name.startsWith("mindustry.game.MapObjectives$")
        || name.equals("mindustry.type.Planet$PlanetData")
        || name.equals("mindustry.type.MapLocales")
        || name.equals("mindustry.type.Weather$WeatherEntry")
        || name.startsWith("mindustry.maps.filters.")
        || name.equals("mindustry.world.blocks.Attributes")
        || name.equals("arc.math.geom.Vec2")
        || name.equals("arc.struct.IntMap")
        || name.equals("arc.struct.ObjectIntMap")
        || name.equals("arc.struct.ObjectFloatMap");
    }

    @Override
    public Collection<String> getAccessibleFields(ReflectionContext context, String className){
        if(!included(className)) return Collections.emptyList();
        ClassReader type = context.getClassSource().get(className);
        if(type == null) return Collections.emptyList();
        ArrayList<String> fields = new ArrayList<>();
        type.getFields().forEach(field -> fields.add(field.getName()));
        return fields;
    }

    @Override
    public Collection<MethodDescriptor> getAccessibleMethods(ReflectionContext context, String className){
        if(!included(className)) return Collections.emptyList();
        ClassReader type = context.getClassSource().get(className);
        if(type == null) return Collections.emptyList();
        ArrayList<MethodDescriptor> methods = new ArrayList<>();
        type.getMethods().forEach(method -> {
            if(method.getDescriptor().getName().equals("<init>")) methods.add(method.getDescriptor());
        });
        return methods;
    }

    @Override
    public boolean isClassFoundByName(ReflectionContext context, String name){
        return included(name) && context.getClassSource().get(name) != null;
    }
}
