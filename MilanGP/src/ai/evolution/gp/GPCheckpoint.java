package ai.evolution.gp;

import ai.evolution.gp.nodes.GPSExpression;
import rts.units.UnitTypeTable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Random;

/**
 * A resumable snapshot: generation, RNG state, every individual, and the config it ran under.
 * Resuming with different maps or opponents is refused because the scores would not be comparable.
 */
public final class GPCheckpoint {
    private GPCheckpoint() {}

    public static void save(Path path, GPPopulation population, GPConfig cfg) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", "2");
        p.setProperty("generation", Integer.toString(population.getGeneration()));
        p.setProperty("random", encodeRandom(population.getRandom()));
        for (String key : cfg.toProperties().stringPropertyNames()) {
            p.setProperty("config." + key, cfg.toProperties().getProperty(key));
        }
        List<GPIndividual> individuals = population.getIndividuals();
        p.setProperty("individual.count", Integer.toString(individuals.size()));
        for (int i = 0; i < individuals.size(); i++) {
            p.setProperty("individual." + i, individuals.get(i).toSExpression());
        }

        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            p.store(writer, "microRTS GP checkpoint");
        }
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static GPPopulation load(Path path, GPConfig cfg, UnitTypeTable utt) throws IOException {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        if (!"2".equals(p.getProperty("version"))) {
            throw new IOException("Unsupported checkpoint version: " + p.getProperty("version"));
        }
        Properties current = cfg.toProperties();
        for (String key : new String[]{"maps", "opponents", "unitTypeTableVersion", "conflictPolicy"}) {
            String saved = p.getProperty("config." + key);
            if (saved != null && !saved.equals(current.getProperty(key))) {
                throw new IOException("Checkpoint " + key + " (" + saved + ") differs from the current config");
            }
        }
        int count = Integer.parseInt(p.getProperty("individual.count"));
        if (count != cfg.populationSize) {
            throw new IOException("Checkpoint holds " + count + " individuals but populationSize is " + cfg.populationSize);
        }
        List<GPIndividual> individuals = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            individuals.add(new GPIndividual(GPSExpression.parseAction(p.getProperty("individual." + i))));
        }
        GPPopulation population = new GPPopulation(cfg, utt, decodeRandom(p.getProperty("random")));
        population.restore(individuals, Integer.parseInt(p.getProperty("generation")));
        return population;
    }

    private static String encodeRandom(Random random) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(random);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static Random decodeRandom(String encoded) throws IOException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            return (Random) in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Cannot restore checkpoint RNG", e);
        }
    }
}
