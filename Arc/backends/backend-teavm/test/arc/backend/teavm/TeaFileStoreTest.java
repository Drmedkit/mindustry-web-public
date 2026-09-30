package arc.backend.teavm;

import java.util.Arrays;
import java.util.List;

/** Dependency-free JVM tests; run with assertions disabled or enabled. */
public final class TeaFileStoreTest{
    private int checks;

    public static void main(String[] args){
        TeaFileStoreTest test = new TeaFileStoreTest();
        test.pathsAreNormalizedAndContained();
        test.preloadAndReadsAreDefensive();
        test.listingIsImmediateAndDeterministic();
        test.writesAppendAndDirectoriesAreJournaled();
        test.deleteIsRecursive();
        test.filesAndDirectoriesCanMove();
        test.journalCanBeAcknowledgedAfterAsyncWork();
        test.typeConflictsDoNotPartiallyMutate();
        test.journalKeepsOnlyTheLatestWritePerPath();
        test.modificationTimesAreTrackedAndOrdered();
        System.out.println("TeaFileStoreTest: " + test.checks + " checks passed");
    }

    private void pathsAreNormalizedAndContained(){
        equal("alpha/beta", TeaFileStore.normalize("./alpha//beta/"));
        equal("", TeaFileStore.normalize(""));
        equal("", TeaFileStore.normalize("./"));
        equal("three...dots", TeaFileStore.normalize("three...dots"));

        rejectsPath(null);
        rejectsPath("/absolute");
        rejectsPath("../escape");
        rejectsPath("safe/../escape");
        rejectsPath("windows\\path");
        rejectsPath("C:/windows/path");
        rejectsPath("nul\0path");
    }

    private void preloadAndReadsAreDefensive(){
        TeaFileStore store = new TeaFileStore();
        byte[] source = bytes(1, 2, 3);
        store.preload("assets/maps/ground-zero.msav", source);
        source[0] = 99;

        array(bytes(1, 2, 3), store.read("assets/maps/ground-zero.msav"));
        byte[] read = store.read("assets/maps/ground-zero.msav");
        read[1] = 88;
        array(bytes(1, 2, 3), store.read("assets/maps/ground-zero.msav"));
        truth(store.exists("assets"), "preload infers parent directories");
        truth(store.isDirectory("assets/maps"), "inferred parent is a directory");
        truth(!store.hasPendingChanges(), "preload must not dirty the store");
        expects(IllegalStateException.class, new Action(){
            @Override
            public void run(){
                store.read("assets/maps");
            }
        });
    }

    private void listingIsImmediateAndDeterministic(){
        TeaFileStore store = new TeaFileStore();
        store.preload("z-last.bin", bytes(0));
        store.preload("maps/zeta/map.msav", bytes(1));
        store.preload("maps/alpha.msav", bytes(2));
        store.preload("a-first.bin", bytes(3));
        store.preloadDirectory("empty");

        equal(Arrays.asList("a-first.bin", "empty", "maps", "z-last.bin"), store.list("."));
        equal(Arrays.asList("alpha.msav", "zeta"), store.list("maps"));
        equal(Arrays.asList("map.msav"), store.list("maps/zeta"));
        equal(Arrays.asList(), store.list("missing"));
        equal(Arrays.asList(), store.list("z-last.bin"));

        final List<String> listing = store.list("");
        expects(UnsupportedOperationException.class, new Action(){
            @Override
            public void run(){
                listing.add("mutation");
            }
        });
    }

    private void writesAppendAndDirectoriesAreJournaled(){
        TeaFileStore store = new TeaFileStore();
        byte[] first = bytes(10, 11);
        store.write("saves/slot/data.bin", first);
        first[0] = 0;
        store.append("saves/slot/data.bin", bytes(12, 13));
        store.mkdirs("saves/empty/nested");

        array(bytes(10, 11, 12, 13), store.read("saves/slot/data.bin"));
        List<TeaFileStore.Change> changes = store.snapshotChanges();
        // The append supersedes the first write, so only the final contents are journaled.
        equal(5, changes.size());
        change(changes.get(0), TeaFileStore.ChangeType.MKDIR, "saves", null);
        change(changes.get(1), TeaFileStore.ChangeType.MKDIR, "saves/slot", null);
        change(changes.get(2), TeaFileStore.ChangeType.WRITE, "saves/slot/data.bin", bytes(10, 11, 12, 13));
        change(changes.get(3), TeaFileStore.ChangeType.MKDIR, "saves/empty", null);
        change(changes.get(4), TeaFileStore.ChangeType.MKDIR, "saves/empty/nested", null);

        byte[] journalData = changes.get(2).getData();
        journalData[0] = 55;
        array(bytes(10, 11, 12, 13), changes.get(2).getData());
        array(bytes(10, 11, 12, 13), store.read("saves/slot/data.bin"));
    }

    private void deleteIsRecursive(){
        TeaFileStore store = new TeaFileStore();
        store.preload("campaign/sector/data", bytes(1));
        store.preload("campaign/other", bytes(2));

        truth(store.delete("campaign/sector"), "existing directory should be deleted");
        truth(!store.exists("campaign/sector"), "directory should be gone");
        truth(!store.exists("campaign/sector/data"), "descendant should be gone");
        truth(store.exists("campaign/other"), "sibling must remain");
        truth(!store.delete("campaign/missing"), "missing delete should report false");
        equal(1, store.snapshotChanges().size());
        change(store.snapshotChanges().get(0), TeaFileStore.ChangeType.DELETE, "campaign/sector", null);
        expects(IllegalArgumentException.class, new Action(){
            @Override
            public void run(){
                store.delete(".");
            }
        });
    }

    private void filesAndDirectoriesCanMove(){
        TeaFileStore store = new TeaFileStore();
        store.preload("maps/one.msav", bytes(7));
        store.move("maps/one.msav", "archive/one.msav");
        truth(!store.exists("maps/one.msav"), "file source should be removed");
        array(bytes(7), store.read("archive/one.msav"));

        store.preload("campaign/sector-a/state", bytes(8));
        store.preloadDirectory("campaign/sector-a/empty");
        store.move("campaign/sector-a", "backup/sector-a");
        truth(!store.exists("campaign/sector-a"), "directory source should be removed");
        array(bytes(8), store.read("backup/sector-a/state"));
        truth(store.isDirectory("backup/sector-a/empty"), "empty directories should move");

        expects(IllegalArgumentException.class, new Action(){
            @Override
            public void run(){
                store.move("backup", "backup/nested");
            }
        });
    }

    private void journalCanBeAcknowledgedAfterAsyncWork(){
        TeaFileStore store = new TeaFileStore();
        store.write("first", bytes(1));
        List<TeaFileStore.Change> flushing = store.snapshotChanges();
        long flushedThrough = flushing.get(flushing.size() - 1).getSequence();

        store.write("second", bytes(2));
        store.acknowledgeChanges(flushedThrough);
        List<TeaFileStore.Change> pending = store.snapshotChanges();
        equal(1, pending.size());
        change(pending.get(0), TeaFileStore.ChangeType.WRITE, "second", bytes(2));

        equal(1, store.drainChanges().size());
        truth(!store.hasPendingChanges(), "drain should clear the journal");
    }

    private void typeConflictsDoNotPartiallyMutate(){
        final TeaFileStore store = new TeaFileStore();
        store.preload("blocked", bytes(1));
        expects(IllegalStateException.class, new Action(){
            @Override
            public void run(){
                store.write("blocked/child/data", bytes(2));
            }
        });
        truth(!store.exists("blocked/child"), "failed write must not create parents");
        truth(!store.hasPendingChanges(), "failed write must not create journal entries");

        store.preloadDirectory("directory");
        expects(IllegalStateException.class, new Action(){
            @Override
            public void run(){
                store.write("directory", bytes(3));
            }
        });
    }

    private void journalKeepsOnlyTheLatestWritePerPath(){
        TeaFileStore store = new TeaFileStore();
        store.preloadDirectory("data");
        // A save stream truncates on open, then writes the full file on close.
        store.write("data/settings.bin", bytes());
        store.write("data/settings.bin", bytes(1, 2, 3));
        store.write("data/other.bin", bytes(4));
        List<TeaFileStore.Change> changes = store.snapshotChanges();
        equal(2, changes.size());
        change(changes.get(0), TeaFileStore.ChangeType.WRITE, "data/settings.bin", bytes(1, 2, 3));
        change(changes.get(1), TeaFileStore.ChangeType.WRITE, "data/other.bin", bytes(4));

        // A write made while an earlier flush is in flight survives its acknowledgement.
        long flushedThrough = changes.get(1).getSequence();
        store.write("data/settings.bin", bytes(9));
        store.acknowledgeChanges(flushedThrough);
        changes = store.snapshotChanges();
        equal(1, changes.size());
        change(changes.get(0), TeaFileStore.ChangeType.WRITE, "data/settings.bin", bytes(9));
        store.drainChanges();

        // Deleting a directory drops pending writes beneath it; later writes remain.
        store.write("saves/slot/a.msav", bytes(1));
        store.write("saves/keep.msav", bytes(2));
        store.delete("saves/slot");
        store.write("saves/slot/b.msav", bytes(3));
        changes = store.snapshotChanges();
        equal(6, changes.size());
        change(changes.get(0), TeaFileStore.ChangeType.MKDIR, "saves", null);
        change(changes.get(1), TeaFileStore.ChangeType.MKDIR, "saves/slot", null);
        change(changes.get(2), TeaFileStore.ChangeType.WRITE, "saves/keep.msav", bytes(2));
        change(changes.get(3), TeaFileStore.ChangeType.DELETE, "saves/slot", null);
        change(changes.get(4), TeaFileStore.ChangeType.MKDIR, "saves/slot", null);
        change(changes.get(5), TeaFileStore.ChangeType.WRITE, "saves/slot/b.msav", bytes(3));
    }

    private void modificationTimesAreTrackedAndOrdered(){
        TeaFileStore store = new TeaFileStore();
        store.preload("backups/old.bin", bytes(1), 1234L);
        equal(1234L, store.lastModified("backups/old.bin"));
        store.preload("legacy.bin", bytes(1));
        equal(0L, store.lastModified("legacy.bin"));

        store.write("backups/a.bin", bytes(1));
        store.write("backups/b.bin", bytes(2));
        long a = store.lastModified("backups/a.bin");
        long b = store.lastModified("backups/b.bin");
        truth(a > 1234L, "a written file gets a wall-clock timestamp");
        truth(b > a, "later writes must sort as newer, even within one millisecond");

        List<TeaFileStore.Change> changes = store.snapshotChanges();
        equal(b, changes.get(changes.size() - 1).getModified());

        store.move("backups/a.bin", "moved/a.bin");
        equal(a, store.lastModified("moved/a.bin"));
        equal(0L, store.lastModified("backups/a.bin"));
        store.delete("moved");
        equal(0L, store.lastModified("moved/a.bin"));
    }

    private void rejectsPath(final String path){
        expects(IllegalArgumentException.class, new Action(){
            @Override
            public void run(){
                TeaFileStore.normalize(path);
            }
        });
    }

    private void change(TeaFileStore.Change actual, TeaFileStore.ChangeType type, String path, byte[] data){
        equal(type, actual.getType());
        equal(path, actual.getPath());
        array(data, actual.getData());
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
        void run();
    }
}
