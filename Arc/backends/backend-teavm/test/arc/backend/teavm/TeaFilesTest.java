package arc.backend.teavm;

import arc.Files.FileType;
import arc.files.Fi;
import arc.util.ArcRuntimeException;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/** Dependency-free JVM contract tests for {@link TeaFiles} and {@link TeaFi}. */
public final class TeaFilesTest{
    private int checks;

    public static void main(String[] args) throws Exception{
        TeaFilesTest test = new TeaFilesTest();
        test.namespacesAreSeparateAndUnsupportedTypesAreRejected();
        test.navigationUsesStableVirtualPaths();
        test.packagedAssetsAreReadableAndListable();
        test.localFilesSupportBytesTextAndStreams();
        test.directoriesAndDeletionFollowArcSemantics();
        test.copyAndMoveStayInsideTheVirtualFilesystem();
        test.packagedHandlesAreReadOnly();
        System.out.println("TeaFilesTest: " + test.checks + " checks passed");
    }

    private void namespacesAreSeparateAndUnsupportedTypesAreRejected(){
        TeaFileStore assets = new TeaFileStore();
        TeaFileStore local = new TeaFileStore();
        assets.preload("same.bin", bytes(1));
        local.preload("same.bin", bytes(2));
        final TeaFiles files = new TeaFiles(assets, local);

        array(bytes(1), files.internal("same.bin").readBytes());
        array(bytes(1), files.classpath("same.bin").readBytes());
        array(bytes(2), files.local("same.bin").readBytes());
        truth(files.getAssetStore() == assets, "asset store should be exposed for hydration");
        truth(files.getLocalStore() == local, "local store should be exposed for persistence");
        equal("internal:/", files.getInternalStoragePath());
        equal("local:/", files.getLocalStoragePath());
        equal("local:/cache", files.getCachePath());
        equal("cache/previews/map.png", files.cache("previews/map.png").path());
        truth(files.isLocalStorageAvailable(), "browser-local storage should be synchronously available");
        truth(!files.isExternalStorageAvailable(), "host external storage must not be advertised");

        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                files.absolute("/tmp/file");
            }
        });
        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                files.external("file");
            }
        });
        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                files.getExternalStoragePath();
            }
        });
        expects(IllegalArgumentException.class, new Action(){
            @Override
            public void run(){
                new TeaFiles(assets, assets);
            }
        });
    }

    private void navigationUsesStableVirtualPaths(){
        TeaFiles files = new TeaFiles();
        Fi file = files.local("./saves//slot-one/data.msav");

        equal(FileType.local, file.type());
        equal("saves/slot-one/data.msav", file.path());
        equal("local:/saves/slot-one/data.msav", file.absolutePath());
        equal("data.msav", file.name());
        equal("msav", file.extension());
        equal("data", file.nameWithoutExtension());
        equal("saves/slot-one/data", file.pathWithoutExtension());
        equal("saves/slot-one", file.parent().path());
        equal("saves/slot-one/meta.json", file.sibling("meta.json").path());
        equal("saves/slot-one/chunks/0.bin", file.parent().child("chunks/0.bin").path());
        equal("", files.local("").parent().path());
        truth(file.equals(files.local("saves/slot-one/data.msav")), "normalized handles should compare equal");
        equal(file.hashCode(), files.local("saves/slot-one/data.msav").hashCode());

        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                new TeaFiles().local("").sibling("other");
            }
        });
        expects(IllegalArgumentException.class, new Action(){
            @Override
            public void run(){
                new TeaFiles().local("safe").child("../escape");
            }
        });
        expects(IllegalArgumentException.class, new Action(){
            @Override
            public void run(){
                new TeaFiles().internal("/absolute");
            }
        });
    }

    private void packagedAssetsAreReadableAndListable() throws Exception{
        TeaFileStore assets = new TeaFileStore();
        assets.preload("maps/zeta.msav", bytes(9, 8, 7));
        assets.preload("maps/alpha.msav", bytes(1, 2, 3, 4));
        assets.preload("maps/readme.txt", bytes(5));
        assets.preloadDirectory("maps/empty");
        TeaFiles files = new TeaFiles(assets, new TeaFileStore());
        Fi maps = files.internal("maps");

        truth(maps.exists(), "packaged directory should exist");
        truth(maps.isDirectory(), "packaged directory should retain its type");
        paths(new String[]{"maps/alpha.msav", "maps/empty", "maps/readme.txt", "maps/zeta.msav"}, maps.list());
        paths(new String[]{"maps/alpha.msav", "maps/zeta.msav"}, maps.list(".msav"));
        paths(new String[]{"maps/readme.txt"}, maps.list(new FilenameFilter(){
            @Override
            public boolean accept(File dir, String name){
                return name.endsWith(".txt");
            }
        }));
        paths(new String[]{"maps/empty"}, maps.list(new FileFilter(){
            @Override
            public boolean accept(File pathname){
                return pathname.getName().equals("empty");
            }
        }));

        Fi alpha = files.classpath("maps/alpha.msav");
        truth(alpha.exists(), "classpath and internal should address packaged assets");
        equal(FileType.classpath, alpha.type());
        equal(4L, alpha.length());
        InputStream input = alpha.read();
        equal(1, input.read());
        equal(2, input.read());
        input.close();

        byte[] destination = bytes(0, 0, 0, 0, 0, 0);
        equal(3, alpha.readBytes(destination, 2, 3));
        array(bytes(0, 0, 1, 2, 3, 0), destination);
        ByteBuffer mapped = alpha.map(FileChannel.MapMode.READ_ONLY);
        truth(!mapped.isDirect(), "browser maps should remain heap-backed");
        equal(4, mapped.remaining());
        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                files.internal("missing").readBytes();
            }
        });
        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                files.internal("maps").read();
            }
        });
    }

    private void localFilesSupportBytesTextAndStreams() throws Exception{
        TeaFiles files = new TeaFiles();
        Fi data = files.local("saves/slot/data.bin");
        byte[] original = bytes(1, 2);
        data.writeBytes(original);
        original[0] = 99;
        data.writeBytes(bytes(3, 4), true);

        truth(data.exists(), "write should create the file");
        truth(data.parent().isDirectory(), "write should infer parent directories");
        equal(4L, data.length());
        array(bytes(1, 2, 3, 4), data.readBytes());

        data.writeBytes(bytes(9, 8, 7, 6), 1, 2, false);
        array(bytes(8, 7), data.readBytes());

        Fi streamed = files.local("streamed.bin");
        OutputStream output = streamed.write(false);
        output.write(bytes(10, 11));
        output.flush();
        array(bytes(10, 11), streamed.readBytes());
        output.write(12);
        output.close();
        output.close();
        array(bytes(10, 11, 12), streamed.readBytes());

        OutputStream appended = streamed.write(true);
        appended.write(13);
        appended.close();
        array(bytes(10, 11, 12, 13), streamed.readBytes());

        Fi text = files.local("settings/name.txt");
        text.writeString("ground zero");
        equal("ground zero", text.readString());
        text.writeString("!", true);
        equal("ground zero!", text.readString());
    }

    private void directoriesAndDeletionFollowArcSemantics(){
        TeaFiles files = new TeaFiles();
        Fi tree = files.local("campaign/sector/one");
        truth(tree.mkdirs(), "mkdirs should create missing directories");
        truth(!tree.mkdirs(), "mkdirs should report no change for an existing directory");
        tree.child("state.bin").writeBytes(bytes(1));
        tree.child("empty").mkdirs();

        truth(!tree.delete(), "delete must not remove a non-empty directory");
        truth(tree.child("state.bin").delete(), "delete should remove a file");
        truth(tree.child("empty").delete(), "delete should remove an empty directory");
        truth(tree.delete(), "directory should be deletable once empty");
        truth(!tree.exists(), "deleted directory should be absent");

        Fi recursive = files.local("recursive");
        recursive.child("a/b/file").writeBytes(bytes(2));
        truth(recursive.deleteDirectory(), "deleteDirectory should recurse");
        truth(!recursive.child("a/b/file").exists(), "recursive descendants should be removed");
        truth(!files.local("").deleteDirectory(), "local root must not be deletable");

        Fi preserved = files.local("preserved");
        preserved.child("nested/file").writeBytes(bytes(3));
        preserved.child("top").writeBytes(bytes(4));
        preserved.emptyDirectory(true);
        truth(preserved.child("nested").isDirectory(), "preserveTree should retain subdirectories");
        truth(!preserved.child("nested/file").exists(), "preserveTree should remove files");
        truth(!preserved.child("top").exists(), "preserveTree should remove top-level files");
        preserved.emptyDirectory(false);
        equal(0, preserved.list().length);
    }

    private void copyAndMoveStayInsideTheVirtualFilesystem(){
        TeaFileStore assets = new TeaFileStore();
        assets.preload("maps/ground.msav", bytes(1, 2, 3));
        assets.preload("campaign/sector/state.bin", bytes(4));
        assets.preloadDirectory("campaign/sector/empty");
        TeaFiles files = new TeaFiles(assets, new TeaFileStore());

        Fi imports = files.local("imports");
        imports.mkdirs();
        files.internal("maps/ground.msav").copyTo(imports);
        array(bytes(1, 2, 3), imports.child("ground.msav").readBytes());

        files.internal("campaign").copyTo(files.local("package"));
        array(bytes(4), files.local("package/campaign/sector/state.bin").readBytes());
        truth(files.local("package/campaign/sector/empty").isDirectory(), "copy should retain empty directories");

        files.internal("campaign").copyFilesTo(files.local("flat"));
        array(bytes(4), files.local("flat/sector/state.bin").readBytes());

        Fi current = files.local("saves/current.msav");
        current.writeBytes(bytes(5));
        current.moveTo(files.local("saves/backup.msav"));
        truth(!current.exists(), "move should remove its file source");
        array(bytes(5), files.local("saves/backup.msav").readBytes());

        Fi archived = files.local("archive");
        archived.mkdirs();
        Fi named = files.local("saves/named.msav");
        named.writeBytes(bytes(6));
        named.moveTo(archived);
        array(bytes(6), archived.child("named.msav").readBytes());

        Fi profile = files.local("profiles/one");
        profile.child("state").writeBytes(bytes(7));
        profile.moveTo(files.local("trash"));
        truth(!profile.exists(), "directory move should remove its source tree");
        array(bytes(7), files.local("trash/one/state").readBytes());

        final Fi source = files.local("source");
        source.child("file").writeBytes(bytes(8));
        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                source.copyTo(source.child("nested"));
            }
        });
        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                source.copyTo(files.internal("readonly"));
            }
        });
        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                source.copyTo(new Fi("unsupported-host-path"));
            }
        });
        truth(!new File("unsupported-host-path").exists(), "copy must not escape to the host filesystem");
    }

    private void packagedHandlesAreReadOnly(){
        TeaFileStore assets = new TeaFileStore();
        assets.preload("asset.bin", bytes(1));
        assets.preloadDirectory("folder");
        final TeaFiles files = new TeaFiles(assets, new TeaFileStore());

        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                files.internal("asset.bin").writeBytes(bytes(2));
            }
        });
        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                files.classpath("new.bin").write(false);
            }
        });
        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                files.internal("new-folder").mkdirs();
            }
        });
        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                files.internal("asset.bin").delete();
            }
        });
        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                files.internal("folder").deleteDirectory();
            }
        });
        expects(ArcRuntimeException.class, new Action(){
            @Override
            public void run(){
                files.internal("asset.bin").moveTo(files.local("moved.bin"));
            }
        });
        array(bytes(1), assets.read("asset.bin"));
        truth(!assets.hasPendingChanges(), "rejected mutations must not dirty packaged assets");
    }

    private void paths(String[] expected, Fi[] actual){
        String[] paths = new String[actual.length];
        for(int i = 0; i < actual.length; i++) paths[i] = actual[i].path();
        equal(Arrays.asList(expected), Arrays.asList(paths));
    }

    private void truth(boolean condition, String message){
        checks++;
        if(!condition) throw new AssertionError(message);
    }

    private void equal(Object expected, Object actual){
        checks++;
        if(expected == null ? actual != null : !expected.equals(actual)){
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private void array(byte[] expected, byte[] actual){
        checks++;
        if(!Arrays.equals(expected, actual)){
            throw new AssertionError("Expected " + Arrays.toString(expected) + " but got " + Arrays.toString(actual));
        }
    }

    private void expects(Class<? extends Throwable> expected, Action action){
        checks++;
        try{
            action.run();
        }catch(Throwable thrown){
            if(expected.isInstance(thrown)) return;
            throw new AssertionError("Expected " + expected.getName() + " but got " + thrown, thrown);
        }
        throw new AssertionError("Expected " + expected.getName() + " to be thrown");
    }

    private static byte[] bytes(int... values){
        byte[] result = new byte[values.length];
        for(int i = 0; i < values.length; i++) result[i] = (byte)values[i];
        return result;
    }

    private interface Action{
        void run() throws Exception;
    }
}
