package io.mindustry.browser;

import arc.Core;
import arc.struct.ObjectSet;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.ctype.Content;
import mindustry.ctype.UnlockableContent;
import mindustry.graphics.Shaders;
import mindustry.graphics.g3d.ShaderSphereMesh;
import mindustry.type.Planet;
import mindustry.world.Block;

/**
 * Keeps the game to Serpulo while every Erekir object stays registered.
 * <p>
 * Content IDs are assigned in load order and multiplayer packets carry raw
 * IDs, so nothing is unregistered: hidden content keeps its ID, saves keep
 * their meaning, and a later build can turn Erekir back on. This class only
 * hides Erekir from what a player can see and pick.
 */
final class SerpuloOnly{
    /**
     * Erekir environment blocks. They have no research requirements, so the
     * game does not tie them to a planet. Several Serpulo maps use a few of
     * these walls; existing tiles still render, they are only left out of the
     * database and the editor palette.
     */
    private static final ObjectSet<String> erekirEnvironment = ObjectSet.with(
        "rhyolite", "rhyolite-crater", "rough-rhyolite", "regolith", "yellow-stone", "carbon-stone",
        "ferric-stone", "ferric-craters", "beryllic-stone", "crystalline-stone", "crystal-floor",
        "yellow-stone-plates", "red-stone", "dense-red-stone", "red-ice", "arkycite-floor", "arkyic-stone",
        "rhyolite-vent", "carbon-vent", "arkyic-vent", "yellow-stone-vent", "red-stone-vent",
        "crystalline-vent", "stone-vent", "basalt-vent", "redmat", "bluemat",
        "regolith-wall", "yellow-stone-wall", "rhyolite-wall", "carbon-wall", "ferric-stone-wall",
        "beryllic-stone-wall", "arkyic-wall", "crystalline-stone-wall", "red-ice-wall", "red-stone-wall",
        "red-diamond-wall", "redweed", "pur-bush", "yellowcoral", "carbon-boulder", "ferric-boulder",
        "beryllic-boulder", "yellow-stone-boulder", "arkyic-boulder", "crystal-cluster",
        "vibrant-crystal-cluster", "crystal-blocks", "crystal-orbs", "crystalline-boulder",
        "red-ice-boulder", "rhyolite-boulder", "red-stone-boulder", "ore-crystal-thorium",
        "ore-wall-thorium", "ore-wall-beryllium", "graphitic-wall", "ore-wall-graphite", "ore-wall-tungsten"
    );

    private SerpuloOnly(){
    }

    /** Planets other than Serpulo and its sun (the solar-system view needs the sun). */
    static boolean hiddenPlanet(Planet planet){
        return planet != Planets.serpulo && planet != Planets.sun;
    }

    /**
     * Runs after base content is created and before it is loaded. Hidden planets
     * get a trivial mesh so startup skips generating Erekir's terrain and the
     * asteroid fields; their sectors and generators are untouched.
     */
    static void afterContentCreated(){
        for(Planet planet : Vars.content.planets()){
            if(!hiddenPlanet(planet)) continue;
            planet.accessible = false;
            planet.visible = false;
            planet.meshLoader = () -> new ShaderSphereMesh(planet, Shaders.unlitWhite, 1);
            planet.cloudMeshLoader = () -> null;
        }
    }

    /** Runs once content is fully initialized (planet associations are only known after postInit). */
    static void afterClientLoad(){
        for(Planet planet : Vars.content.planets()){
            if(hiddenPlanet(planet)){
                planet.accessible = false;
                planet.visible = false;
            }
        }
        for(var contents : Vars.content.getContentMap()){
            for(Content content : contents){
                if(!(content instanceof UnlockableContent u)) continue;
                if(isErekirOnly(u)){
                    u.hideDatabase = true;
                    if(u instanceof Block block) block.inEditor = false;
                }
                // No database tab for a planet that cannot be visited.
                for(Planet planet : Vars.content.planets()){
                    if(hiddenPlanet(planet)) u.databaseTabs.remove(planet);
                }
            }
        }
        Core.settings.put("campaignselect", true);
        Core.settings.put("lastplanet", Planets.serpulo.name);
    }

    /**
     * Every content object as JSON lines: type, numeric ID, name and whether it
     * is hidden as Erekir-only. Used to split the sprite atlas and to snapshot
     * content IDs, which must match vanilla Mindustry for multiplayer.
     */
    static String contentSnapshot(){
        StringBuilder out = new StringBuilder("[\n");
        boolean first = true;
        for(var contents : Vars.content.getContentMap()){
            for(Content content : contents){
                if(!(content instanceof mindustry.ctype.MappableContent mappable)) continue;
                if(!first) out.append(",\n");
                first = false;
                out.append("{\"type\":\"").append(content.getContentType().name())
                    .append("\",\"id\":").append(content.id)
                    .append(",\"name\":\"").append(mappable.name)
                    .append("\",\"erekirOnly\":").append(content instanceof UnlockableContent u && isErekirOnly(u))
                    .append('}');
            }
        }
        return out.append("\n]\n").toString();
    }

    /** Whether a region comes from an atlas page the browser never downloads (a 1x1 placeholder). */
    static boolean isPlaceholder(arc.graphics.g2d.TextureRegion region){
        return region != null && region.texture != null && region.texture.width <= 1 && region.texture.height <= 1;
    }

    /** Visible content whose icons would draw from a never-downloaded page. Should be empty. */
    static arc.struct.Seq<String> visibleContentOnPlaceholders(){
        arc.struct.Seq<String> broken = new arc.struct.Seq<>();
        for(var contents : Vars.content.getContentMap()){
            for(Content content : contents){
                if(!(content instanceof UnlockableContent u) || isErekirOnly(u) || u.isHidden()) continue;
                if(isPlaceholder(u.uiIcon) || isPlaceholder(u.fullIcon) || (u instanceof Block block && isPlaceholder(block.region))){
                    broken.add(u.name);
                }
            }
        }
        return broken;
    }

    /** Blocks on the loaded map (floors, overlays, buildings) that would draw from a never-downloaded page. */
    static arc.struct.ObjectSet<String> worldBlocksOnPlaceholders(){
        arc.struct.ObjectSet<String> broken = new arc.struct.ObjectSet<>();
        for(mindustry.world.Tile tile : Vars.world.tiles){
            for(Block block : new Block[]{tile.floor(), tile.overlay(), tile.block()}){
                if(block == null || block == mindustry.content.Blocks.air) continue;
                boolean placeholder = isPlaceholder(block.region) || isPlaceholder(block.fullIcon);
                if(block instanceof mindustry.world.blocks.environment.Floor floor && floor.variantRegions != null){
                    for(var region : floor.variantRegions) placeholder |= isPlaceholder(region);
                }
                if(placeholder) broken.add(block.name);
            }
        }
        return broken;
    }

    static boolean isErekirOnly(UnlockableContent content){
        if(erekirEnvironment.contains(content.name)) return true;
        if(content.shownPlanets.isEmpty() || content.shownPlanets.contains(Planets.serpulo)) return false;
        for(Planet planet : content.shownPlanets){
            if(!hiddenPlanet(planet)) return false;
        }
        return true;
    }

    /**
     * Checks a freshly loaded world. "Any environment" (the sun) plays as
     * Serpulo; a map made for a hidden planet is refused.
     *
     * @return false if the world must not be played in this build
     */
    static boolean acceptWorld(){
        Planet planet = Vars.state.rules.planet;
        if(planet == Planets.sun){
            Vars.state.rules.planet = Planets.serpulo;
            return true;
        }
        return planet == null || !hiddenPlanet(planet);
    }
}
