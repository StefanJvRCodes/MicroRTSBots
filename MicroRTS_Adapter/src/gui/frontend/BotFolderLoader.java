package gui.frontend;

import ai.core.AI;
import ai.custom.GP_bot.GPEvolvedBot;
import ai.custom.GP_bot.Node;
import ai.custom.GP_bot.Tree;
import ai.custom.GP_bot.TreePolicy;
import ai.custom.adapter.AdapterBot;
import ai.custom.adapter.AdapterModel;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import rts.units.UnitTypeTable;

/**
 * Finds the bots in the GUI's bot folders when the GUI starts, so a bot is
 * added by dropping its file into a folder and reopening the GUI.
 *
 * <p>Folders, relative to the GUI's home (the folder holding the GUI jar, or
 * the working directory when run from classes): {@code bots/} and the older
 * {@code lib/bots/}; the system property {@code microrts.bots} adds more,
 * separated by the path separator. Subfolders are searched too. Two kinds of
 * file are understood:
 * <ul>
 * <li>{@code .jar}: every public, concrete {@link AI} class in it with a
 * public {@code (UnitTypeTable)} or no-argument constructor.</li>
 * <li>{@code .ser}: a GP tree saved by GP ({@code bestTree.ser}), a population
 * ({@code self_play_final.ser}) or a GP checkpoint, played by
 * {@link GPEvolvedBot} (from a population, the tree with the best win rate);
 * or an Adapter-RL model ({@code adapter_final.ser}, {@code adapter_latest.ser})
 * or adapter training checkpoint, played by {@link AdapterBot} and listed a
 * second time with the adapter switched off, to watch the base tree alone.
 * Trees must use this project's seven output cells; twelve-cell trees from
 * the MicroRTS project are refused with a message. The file is read when the
 * bot is first chosen, not at startup, since checkpoints can be gigabytes.</li>
 * </ul>
 */
public final class BotFolderLoader {

    /** Something that builds a fresh bot for a game. */
    @FunctionalInterface
    public interface Factory {
        AI create(UnitTypeTable utt) throws Exception;
    }

    /** One entry in the GUI's bot list. */
    public static final class ExternalBot {
        /** Name shown in the combobox. */
        public final String label;
        /** Where it came from, shown under the combobox. */
        public final String source;
        private final Factory factory;

        ExternalBot(String label, String source, Factory factory) {
            this.label = label;
            this.source = source;
            this.factory = factory;
        }

        public AI create(UnitTypeTable utt) throws Exception {
            return factory.create(utt);
        }
    }

    private BotFolderLoader() {
    }

    /** The folder holding the GUI jar, or the working directory when the GUI runs from a classes folder. */
    public static File homeDir() {
        try {
            File location = new File(BotFolderLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (location.isFile()) {
                return location.getAbsoluteFile().getParentFile();
            }
        } catch (Exception e) {
            // fall through to the working directory
        }
        return new File(System.getProperty("user.dir")).getAbsoluteFile();
    }

    /** {@code bots/}, {@code lib/bots/} and any folders named by the {@code microrts.bots} system property. */
    public static List<File> defaultFolders() {
        File home = homeDir();
        List<File> folders = new ArrayList<>();
        folders.add(new File(home, "bots"));
        folders.add(new File(home, "lib" + File.separator + "bots"));
        String extra = System.getProperty("microrts.bots");
        if (extra != null && !extra.isEmpty()) {
            for (String path : extra.split(File.pathSeparator)) {
                if (!path.isEmpty()) {
                    folders.add(new File(path));
                }
            }
        }
        return folders;
    }

    public static List<ExternalBot> loadDefaultFolders() {
        return load(defaultFolders());
    }

    /** Every bot found in {@code folders}, sorted by name. Problems are printed and skipped, never thrown. */
    public static List<ExternalBot> load(List<File> folders) {
        List<File> jars = new ArrayList<>();
        List<File> serFiles = new ArrayList<>();
        Set<String> seenFolders = new LinkedHashSet<>();
        for (File folder : folders) {
            try {
                if (folder.isDirectory() && seenFolders.add(folder.getCanonicalPath())) {
                    collect(folder, jars, serFiles);
                }
            } catch (IOException e) {
                System.err.println("[bots] Can't read " + folder + ": " + e.getMessage());
            }
        }

        List<ExternalBot> bots = new ArrayList<>();
        for (File jar : jars) {
            addJarBots(jar, bots);
        }
        for (File ser : serFiles) {
            addTreeBot(ser, bots);
        }
        Collections.sort(bots, (a, b) -> a.label.compareToIgnoreCase(b.label));
        System.out.println("[bots] " + bots.size() + " bot(s) from " + seenFolders);
        return bots;
    }

    private static void collect(File dir, List<File> jars, List<File> serFiles) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        java.util.Arrays.sort(children);
        for (File f : children) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (f.isDirectory()) {
                collect(f, jars, serFiles);
            } else if (name.endsWith(".jar")) {
                jars.add(f);
            } else if (name.endsWith(".ser")) {
                serFiles.add(f);
            }
        }
    }

    // ------------------------------------------------------------------
    // .jar
    // ------------------------------------------------------------------

    private static void addJarBots(File jar, List<ExternalBot> bots) {
        URLClassLoader loader;
        try {
            loader = new URLClassLoader(new URL[] {jar.toURI().toURL()}, BotFolderLoader.class.getClassLoader());
        } catch (IOException e) {
            System.err.println("[bots] Can't open " + jar.getName() + ": " + e.getMessage());
            return;
        }
        int before = bots.size();
        try (JarFile jarFile = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.contains("$") || name.startsWith("META-INF/")) {
                    continue;
                }
                addIfBot(name.substring(0, name.length() - 6).replace('/', '.'), loader, jar.getName(), bots);
            }
        } catch (IOException e) {
            System.err.println("[bots] Can't read " + jar.getName() + ": " + e.getMessage());
        }
        System.out.println("[bots] " + jar.getName() + ": " + (bots.size() - before) + " bot class(es)");
    }

    // ------------------------------------------------------------------
    // Classes from .jar
    // ------------------------------------------------------------------

    private static void addIfBot(String className, ClassLoader loader, String fileName, List<ExternalBot> bots) {
        try {
            addIfBotOrThrow(className, loader, fileName, bots);
        } catch (Throwable t) {
            // Looking at a class can still fail once its constructors are resolved (a helper class that needs
            // something missing from the jar). One broken class must not stop the GUI from starting.
            System.err.println("[bots] Skipping " + className + " in " + fileName + ": " + t);
        }
    }

    private static void addIfBotOrThrow(String className, ClassLoader loader, String fileName, List<ExternalBot> bots) {
        Class<?> c;
        try {
            c = Class.forName(className, false, loader);
        } catch (Throwable t) {
            // Helper classes that need something missing (NoClassDefFoundError, VerifyError, ...) are not bots.
            return;
        }
        if (!AI.class.isAssignableFrom(c) || c.isInterface() || Modifier.isAbstract(c.getModifiers())
                || !Modifier.isPublic(c.getModifiers())) {
            return;
        }
        if (c.getClassLoader() != loader) {
            // The same class is already part of the GUI (e.g. GPEvolvedBot bundled in an exported bot jar); it is
            // listed with the built-in bots, and this copy would resolve to that one anyway.
            return;
        }
        Constructor<?> withUtt = null;
        Constructor<?> noArgs = null;
        try {
            withUtt = c.getConstructor(UnitTypeTable.class);
        } catch (NoSuchMethodException | SecurityException e) {
            // try the no-argument constructor
        }
        if (withUtt == null) {
            try {
                noArgs = c.getConstructor();
            } catch (NoSuchMethodException | SecurityException e) {
                return;
            }
        }
        Constructor<?> uttCons = withUtt;
        Constructor<?> plainCons = noArgs;
        bots.add(new ExternalBot(c.getSimpleName() + "  (" + fileName + ")", fileName,
                utt -> (AI) (uttCons != null ? uttCons.newInstance(utt) : plainCons.newInstance())));
    }

    // ------------------------------------------------------------------
    // .ser
    // ------------------------------------------------------------------

    private static void addTreeBot(File ser, List<ExternalBot> bots) {
        String name = ser.getName().substring(0, ser.getName().length() - 4);
        String parent = ser.getParentFile() == null ? "" : ser.getParentFile().getName();
        // bestTree.ser, adapter_final.ser and checkpoint.ser recur across runs; their folder tells them apart.
        boolean generic = name.equals("bestTree") || name.startsWith("adapter_") || name.equals("checkpoint")
                || name.equals("self_play_final");
        String label = generic && !parent.isEmpty() ? parent + "/" + name : name;
        Object[] cache = new Object[1];
        if (isAdapterFile(ser)) {
            bots.add(new ExternalBot(label + "  (adapter)", ser.getName(),
                    utt -> new AdapterBot(adapterModel(ser, cache), utt, true, false, false, System.nanoTime())));
            bots.add(new ExternalBot(label + "  (adapter off: base tree alone)", ser.getName(),
                    utt -> new AdapterBot(adapterModel(ser, cache), utt, false, false, false, System.nanoTime())));
        } else {
            bots.add(new ExternalBot(label + "  (GP tree)", ser.getName(),
                    utt -> new GPEvolvedBot(new Tree(gpTree(ser, cache).root.deepCopy(), TreePolicy.NUM_OUTPUT_CELLS), utt)));
        }
    }

    /** True when the file's top-level object is an Adapter-RL model or adapter training checkpoint. */
    private static boolean isAdapterFile(File ser) {
        String top = topClassName(ser);
        return top != null && top.startsWith("ai.custom.adapter.");
    }

    /** The class name in a Java serialization stream's first object header, without reading the rest. */
    static String topClassName(File ser) {
        try (DataInputStream in = new DataInputStream(new FileInputStream(ser))) {
            if (in.readUnsignedShort() != 0xACED) {
                return null;
            }
            in.readUnsignedShort();                       // stream version
            int tag = in.readUnsignedByte();              // TC_OBJECT 0x73 or TC_ARRAY 0x75
            if ((tag != 0x73 && tag != 0x75) || in.readUnsignedByte() != 0x72) {   // TC_CLASSDESC
                return null;
            }
            return in.readUTF();
        } catch (IOException e) {
            return null;
        }
    }

    private static Object readOnce(File ser, Object[] cache) throws Exception {
        synchronized (cache) {
            if (cache[0] == null) {
                System.out.printf("[bots] Reading %s (%.1f MB)%n", ser, ser.length() / 1e6);
                try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                        new java.io.BufferedInputStream(new FileInputStream(ser), 1 << 16))) {
                    cache[0] = in.readObject();
                }
            }
            return cache[0];
        }
    }

    private static AdapterModel adapterModel(File ser, Object[] cache) throws Exception {
        Object o = readOnce(ser, cache);
        if (o instanceof AdapterModel) {
            return (AdapterModel) o;
        }
        java.lang.reflect.Field f = o.getClass().getDeclaredField("model");   // AdapterTrainer's checkpoint
        f.setAccessible(true);
        return (AdapterModel) f.get(o);
    }

    /** The tree in a GP file (the best of a population or checkpoint), checked to fit this project's 7 cells. */
    private static Tree gpTree(File ser, Object[] cache) throws Exception {
        Object o = readOnce(ser, cache);
        Tree[] trees;
        if (o instanceof Tree) {
            trees = new Tree[] {(Tree) o};
        } else if (o instanceof Tree[]) {
            trees = (Tree[]) o;
        } else {
            java.lang.reflect.Field f = o.getClass().getDeclaredField("population");
            f.setAccessible(true);
            trees = (Tree[]) f.get(o);
        }
        java.lang.reflect.Field winRate = Tree.class.getDeclaredField("winRate");
        winRate.setAccessible(true);
        Tree best = null;
        for (Tree t : trees) {
            if (t != null && t.root != null && (best == null
                    || (Double.isFinite(winRate.getDouble(t)) && winRate.getDouble(t) > winRate.getDouble(best)))) {
                best = t;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException(ser.getName() + " has no usable tree");
        }
        int maxCell = maxModiCell(best.root);
        if (maxCell >= TreePolicy.NUM_OUTPUT_CELLS) {
            throw new IllegalArgumentException(ser.getName() + " uses output cell " + maxCell + ", so it comes from the "
                    + "12-cell GP of the MicroRTS project, but this project's bot has " + TreePolicy.NUM_OUTPUT_CELLS
                    + " cells. Open it with the MicroRTS GUI instead.");
        }
        return best;
    }

    private static int maxModiCell(Node root) {
        int max = -1;
        java.util.ArrayDeque<Node> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            if (n.type == Node.NodeType.FUNCTION && n.isModi) {
                max = Math.max(max, n.outputCellIndex);
            }
            if (n.children != null) {
                for (Node c : n.children) {
                    if (c != null) {
                        stack.push(c);
                    }
                }
            }
        }
        return max;
    }
}
