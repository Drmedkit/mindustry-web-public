import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates Mindustry's reflection-free registry for built-in Block building factories.
 *
 * <p>This intentionally uses only the public javac syntax-tree API. It can therefore be
 * run from a source checkout before Mindustry, Arc, or any generated entity classes have
 * been compiled.</p>
 */
public final class BlockBuildFactoryGenerator{
    private static final String blockType = "mindustry.world.Block";
    private static final String buildingType = "mindustry.gen.Building";
    private final Map<String, TypeInfo> byName = new LinkedHashMap<>();
    private final Map<String, List<TypeInfo>> bySimpleName = new HashMap<>();
    private final Map<TypeInfo, Boolean> blockCache = new HashMap<>();
    private final Map<TypeInfo, Boolean> buildingCache = new HashMap<>();
    private final List<String> errors = new ArrayList<>();

    public static void main(String[] args) throws Exception{
        boolean write = false;
        Path project = Paths.get("").toAbsolutePath().normalize();

        for(String arg : args){
            if(arg.equals("--write")){
                write = true;
            }else if(arg.equals("--check")){
                write = false;
            }else if(arg.startsWith("--project=")){
                project = Paths.get(arg.substring("--project=".length())).toAbsolutePath().normalize();
            }else{
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        new BlockBuildFactoryGenerator().run(project, write);
    }

    private void run(Path project, boolean write) throws Exception{
        Path sourceRoot = project.resolve("Mindustry/core/src");
        Path worldRoot = sourceRoot.resolve("mindustry/world");
        Path output = sourceRoot.resolve("mindustry/world/BuiltinBlockBuildFactories.java");

        if(!Files.isDirectory(worldRoot)){
            throw new IllegalArgumentException("Mindustry source tree not found under " + project);
        }

        List<Path> sources;
        try(var paths = Files.walk(sourceRoot)){
            sources = paths.filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .collect(Collectors.toList());
        }

        // The output participates in normal compilation, but must not become input to itself.
        sources.remove(output);
        parse(sources);

        TypeInfo block = byName.get(blockType);
        if(block == null){
            errors.add("Could not find " + blockType);
        }

        List<FactoryEntry> entries = collectEntries();
        if(!errors.isEmpty()){
            throw new IllegalStateException("Cannot generate block factories:\n - " + String.join("\n - ", errors));
        }

        String generated = generate(entries);
        if(write){
            Files.createDirectories(output.getParent());
            Files.writeString(output, generated, StandardCharsets.UTF_8);
            System.out.println("Wrote " + relative(project, output) + " with " + entries.size() + " block mappings.");
        }else{
            if(!Files.exists(output)){
                throw new IllegalStateException(relative(project, output) + " does not exist; run with --write.");
            }
            // Compare content, not the checkout's line endings.
            String current = Files.readString(output, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if(!current.equals(generated)){
                throw new IllegalStateException(relative(project, output) + " is stale; run with --write.");
            }
            System.out.println("PASS: " + entries.size() + " block mappings are complete and deterministic.");
        }
    }

    private void parse(List<Path> sources) throws IOException{
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if(compiler == null){
            throw new IllegalStateException("A full JDK is required (ToolProvider has no Java compiler).");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try(StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)){
            Iterable<? extends JavaFileObject> inputs = files.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask)compiler.getTask(null, files, diagnostics,
                List.of("-proc:none", "-Xlint:none"), null, inputs);

            for(CompilationUnitTree unit : task.parse()){
                UnitInfo unitInfo = new UnitInfo(unit);
                for(Tree declaration : unit.getTypeDecls()){
                    if(declaration instanceof ClassTree type){
                        collect(type, null, unitInfo);
                    }
                }
            }
        }

        for(Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()){
            if(diagnostic.getKind() == Diagnostic.Kind.ERROR){
                errors.add("javac parser: " + diagnostic.getMessage(Locale.ROOT));
            }
        }
    }

    private void collect(ClassTree tree, TypeInfo owner, UnitInfo unit){
        String simpleName = tree.getSimpleName().toString();
        if(simpleName.isEmpty()) return; // anonymous class

        String canonicalName = owner == null
            ? unit.packageName + "." + simpleName
            : owner.canonicalName + "." + simpleName;

        TypeInfo type = new TypeInfo(tree, owner, unit, simpleName, canonicalName, rawType(tree.getExtendsClause()));
        TypeInfo duplicate = byName.put(canonicalName, type);
        if(duplicate != null){
            errors.add("Duplicate source type " + canonicalName);
        }
        bySimpleName.computeIfAbsent(simpleName, ignored -> new ArrayList<>()).add(type);
        if(owner != null) owner.children.add(type);

        for(Tree member : tree.getMembers()){
            if(member instanceof ClassTree child){
                collect(child, type, unit);
            }else if(member instanceof MethodTree method && method.getReturnType() == null){
                type.constructors.add(method);
            }
        }
    }

    private List<FactoryEntry> collectEntries(){
        List<TypeInfo> blocks = byName.values().stream()
            .filter(this::isBlock)
            .filter(type -> type.owner == null || isSourceAccessible(type))
            .sorted(Comparator.comparingInt(this::blockDepth).reversed().thenComparing(type -> type.canonicalName))
            .collect(Collectors.toList());

        for(TypeInfo type : byName.values()){
            if(isBlock(type) && !isSourceAccessible(type)){
                errors.add("Built-in Block type is not accessible to the generated registry: " + type.canonicalName);
            }
        }

        List<FactoryEntry> result = new ArrayList<>();
        for(TypeInfo outer : blocks){
            TypeInfo builder = effectiveBuilder(outer);

            if(builder != null){
                if(!isSourceAccessible(builder)){
                    errors.add("Building type is not accessible to the generated registry: " + builder.canonicalName);
                    continue;
                }
                if(builder.modifiers.contains(Modifier.STATIC)){
                    errors.add("Block.initBuilding() expects a non-static inner Building, but found " + builder.canonicalName);
                    continue;
                }
                if(!hasNoArgSourceConstructor(builder)){
                    // This mirrors Block's current behavior: constructor lookup fails and the catch block
                    // leaves the block on Building::create instead of continuing up the hierarchy.
                    builder = null;
                }
            }

            if(builder != null && builder.modifiers.contains(Modifier.ABSTRACT) && !outer.modifiers.contains(Modifier.ABSTRACT)){
                errors.add("Concrete Block " + outer.canonicalName + " resolves to abstract Building " + builder.canonicalName);
                continue;
            }

            result.add(new FactoryEntry(outer, builder));
        }

        if(result.stream().noneMatch(entry -> entry.outer.canonicalName.equals(blockType))){
            errors.add("The generated registry does not cover the base Block type.");
        }
        return result;
    }

    private TypeInfo effectiveBuilder(TypeInfo outer){
        Set<TypeInfo> seen = new HashSet<>();
        TypeInfo current = outer;
        while(current != null && seen.add(current) && isBlock(current)){
            List<TypeInfo> builders = current.children.stream().filter(this::isBuilding).collect(Collectors.toList());
            if(builders.size() > 1){
                errors.add(current.canonicalName + " declares multiple Building subclasses; reflection order would be unstable: "
                    + builders.stream().map(type -> type.canonicalName).collect(Collectors.joining(", ")));
                return null;
            }
            if(builders.size() == 1) return builders.get(0);
            current = resolve(current.superName, current);
        }
        return null;
    }

    private boolean hasNoArgSourceConstructor(TypeInfo type){
        return type.constructors.isEmpty() || type.constructors.stream().anyMatch(constructor -> constructor.getParameters().isEmpty());
    }

    private boolean isBlock(TypeInfo type){
        Boolean cached = blockCache.get(type);
        if(cached != null) return cached;
        if(type.canonicalName.equals(blockType)) return true;

        blockCache.put(type, false); // cycle guard
        TypeInfo parent = resolve(type.superName, type);
        boolean result = parent != null && isBlock(parent);
        blockCache.put(type, result);
        return result;
    }

    private boolean isBuilding(TypeInfo type){
        Boolean cached = buildingCache.get(type);
        if(cached != null) return cached;
        if(type.canonicalName.equals(buildingType)) return true;
        if(simple(type.superName).equals("Building") || type.superName.equals(buildingType)) return true;

        buildingCache.put(type, false); // cycle guard
        TypeInfo parent = resolve(type.superName, type);
        boolean result = parent != null && isBuilding(parent);
        buildingCache.put(type, result);
        return result;
    }

    private int blockDepth(TypeInfo type){
        int depth = 0;
        Set<TypeInfo> seen = new HashSet<>();
        TypeInfo current = type;
        while(current != null && !current.canonicalName.equals(blockType) && seen.add(current)){
            current = resolve(current.superName, current);
            depth++;
        }
        return depth;
    }

    private TypeInfo resolve(String name, TypeInfo context){
        if(name == null || name.isEmpty()) return null;

        TypeInfo exact = byName.get(name);
        if(exact != null) return exact;

        // Resolve a nested source name relative to each lexical owner.
        TypeInfo lexical = context;
        while(lexical != null){
            TypeInfo nested = byName.get(lexical.canonicalName + "." + name);
            if(nested != null) return nested;
            lexical = lexical.owner;
        }

        TypeInfo samePackage = byName.get(context.unit.packageName + "." + name);
        if(samePackage != null) return samePackage;

        String first = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String suffix = name.contains(".") ? name.substring(name.indexOf('.')) : "";
        String imported = context.unit.explicitImports.get(first);
        if(imported != null){
            TypeInfo resolved = byName.get(imported + suffix);
            if(resolved != null) return resolved;
        }

        List<TypeInfo> wildcardMatches = new ArrayList<>();
        for(String wildcard : context.unit.wildcardImports){
            TypeInfo candidate = byName.get(wildcard + "." + name);
            if(candidate != null) wildcardMatches.add(candidate);
        }
        if(wildcardMatches.size() == 1) return wildcardMatches.get(0);

        List<TypeInfo> simpleMatches = bySimpleName.get(simple(name));
        if(simpleMatches != null && simpleMatches.size() == 1) return simpleMatches.get(0);

        if(name.contains(".")){
            List<TypeInfo> suffixMatches = byName.values().stream()
                .filter(candidate -> candidate.canonicalName.endsWith("." + name))
                .collect(Collectors.toList());
            if(suffixMatches.size() == 1) return suffixMatches.get(0);
        }
        return null;
    }

    private boolean isSourceAccessible(TypeInfo type){
        TypeInfo current = type;
        while(current != null){
            if(current.modifiers.contains(Modifier.PRIVATE)) return false;
            if(!current.modifiers.contains(Modifier.PUBLIC) && !current.unit.packageName.equals("mindustry.world")){
                return false;
            }
            current = current.owner;
        }
        return true;
    }

    private String generate(List<FactoryEntry> entries){
        StringBuilder out = new StringBuilder();
        out.append("// Generated by scripts/src/BlockBuildFactoryGenerator.java. Do not edit manually.\n")
            .append("package mindustry.world;\n\n")
            .append("import mindustry.gen.*;\n\n")
            .append("/** Reflection-free building factories for Mindustry's built-in Block classes. */\n")
            .append("public final class BuiltinBlockBuildFactories{\n")
            .append("    private BuiltinBlockBuildFactories(){\n")
            .append("    }\n\n")
            .append("    /** Installs the generated mapping in strict mode. Call before base content is created. */\n")
            .append("    public static void install(){\n")
            .append("        Block.setBuildTypeInitializer(BuiltinBlockBuildFactories::initialize, true);\n")
            .append("    }\n\n")
            .append("    /** Number of named Block classes covered by this generated source snapshot. */\n")
            .append("    public static int size(){\n")
            .append("        return ").append(entries.size()).append(";\n")
            .append("    }\n\n")
            .append("    /** Assigns the factory and non-anonymous subclass identity used by Block. */\n")
            .append("    public static boolean initialize(Block block){\n");

        for(FactoryEntry entry : entries){
            TypeInfo outer = entry.outer;
            TypeInfo builder = entry.builder;
            out.append("        if(block instanceof ").append(outer.canonicalName).append("){\n")
                .append("            ").append(outer.canonicalName).append(" typed = (")
                .append(outer.canonicalName).append(")block;\n")
                .append("            block.subclass = ").append(outer.canonicalName).append(".class;\n");

            if(builder == null){
                out.append("            block.buildType = Building::create;\n");
            }else if(builder.modifiers.contains(Modifier.ABSTRACT)){
                out.append("            block.buildType = () -> { throw new IllegalStateException(\"Cannot instantiate abstract building type ")
                    .append(builder.canonicalName).append("\"); };\n");
            }else{
                out.append("            block.buildType = () -> typed.new ").append(builder.simpleName).append("();\n");
            }

            out.append("            return true;\n")
                .append("        }\n");
        }

        out.append("        return false;\n")
            .append("    }\n")
            .append("}\n");
        return out.toString();
    }

    private static String rawType(Tree tree){
        if(tree == null) return "";
        if(tree instanceof ParameterizedTypeTree parameterized) return rawType(parameterized.getType());
        if(tree instanceof AnnotatedTypeTree annotated) return rawType(annotated.getUnderlyingType());
        return tree.toString().replace('$', '.');
    }

    private static String simple(String name){
        int index = name.lastIndexOf('.');
        return index < 0 ? name : name.substring(index + 1);
    }

    private static String relative(Path project, Path path){
        try{
            return project.relativize(path).toString();
        }catch(IllegalArgumentException ignored){
            return path.toString();
        }
    }

    private static final class UnitInfo{
        final String packageName;
        final Map<String, String> explicitImports = new HashMap<>();
        final Set<String> wildcardImports = new LinkedHashSet<>();

        UnitInfo(CompilationUnitTree unit){
            packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            for(ImportTree importTree : unit.getImports()){
                if(importTree.isStatic()) continue;
                String imported = importTree.getQualifiedIdentifier().toString();
                if(imported.endsWith(".*")){
                    wildcardImports.add(imported.substring(0, imported.length() - 2));
                }else{
                    explicitImports.put(simple(imported), imported);
                }
            }
        }
    }

    private static final class TypeInfo{
        final TypeInfo owner;
        final UnitInfo unit;
        final String simpleName;
        final String canonicalName;
        final String superName;
        final Set<Modifier> modifiers;
        final List<TypeInfo> children = new ArrayList<>();
        final List<MethodTree> constructors = new ArrayList<>();

        TypeInfo(ClassTree tree, TypeInfo owner, UnitInfo unit, String simpleName, String canonicalName, String superName){
            this.owner = owner;
            this.unit = unit;
            this.simpleName = simpleName;
            this.canonicalName = canonicalName;
            this.superName = superName;
            this.modifiers = tree.getModifiers().getFlags();
        }
    }

    private static final class FactoryEntry{
        final TypeInfo outer;
        final TypeInfo builder;

        FactoryEntry(TypeInfo outer, TypeInfo builder){
            this.outer = outer;
            this.builder = builder;
        }
    }
}
