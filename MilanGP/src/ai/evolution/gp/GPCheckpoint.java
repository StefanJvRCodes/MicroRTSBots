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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

public final class GPCheckpoint {
    private GPCheckpoint() {}

    public static void save(Path path, GPPopulation population) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", "1");
        p.setProperty("generation", Integer.toString(population.getGeneration()));
        p.setProperty("curriculum.completed", Boolean.toString(population.isCurriculumCompleted()));
        p.setProperty("random", encodeRandom(population.getRandom()));
        GPConfig savedConfig = population.getConfig();
        p.setProperty("config.maps", String.join(",", savedConfig.maps));
        p.setProperty("config.opponents", String.join(",", savedConfig.opponents));
        p.setProperty("config.utt", Integer.toString(savedConfig.unitTypeTableVersion));
        p.setProperty("config.conflict", Integer.toString(savedConfig.conflictPolicy));
        p.setProperty("config.evaluationSeed", Long.toString(savedConfig.evaluationSeed));
        p.setProperty("config.maxCycles", Integer.toString(savedConfig.maxCycles));
        p.setProperty("config.maxInactiveCycles", Integer.toString(savedConfig.maxInactiveCycles));
        p.setProperty("config.sampledMatchups", Integer.toString(savedConfig.sampledMatchupsPerGeneration));
        p.setProperty("config.novelty", Boolean.toString(savedConfig.useNoveltyBonus));
        p.setProperty("config.hardCases", Boolean.toString(savedConfig.useHardCaseArchive));

        List<GPIndividual> individuals = population.getIndividuals();
        p.setProperty("individual.count", Integer.toString(individuals.size()));
        for (int i = 0; i < individuals.size(); i++) {
            p.setProperty("individual." + i, individuals.get(i).toSExpression());
        }
        GPIndividual generalistChampion = population.getGeneralistChampion();
        if (generalistChampion != null) {
            p.setProperty("generalist.champion", generalistChampion.toSExpression());
        }
        GPIndividual curriculumChampion = population.getCurriculumChampion();
        if (curriculumChampion != null) {
            p.setProperty("curriculum.champion", curriculumChampion.toSExpression());
        }
        List<GPIndividual> seededSpecialists = population.getSeededSpecialists();
        p.setProperty("seeded.count", Integer.toString(seededSpecialists.size()));
        for (int i = 0; i < seededSpecialists.size(); i++) {
            p.setProperty("seeded." + i, seededSpecialists.get(i).toSExpression());
        }

        Map<String, Double> elo = population.eloSnapshot();
        p.setProperty("elo.count", Integer.toString(elo.size()));
        int i = 0;
        for (Map.Entry<String, Double> entry : elo.entrySet()) {
            p.setProperty("elo." + i + ".key", entry.getKey());
            p.setProperty("elo." + i + ".rating", Double.toString(entry.getValue()));
            i++;
        }

        List<GPHardCaseArchive.Case> hardCases = population.hardCaseSnapshot();
        p.setProperty("hard.count", Integer.toString(hardCases.size()));
        for (i = 0; i < hardCases.size(); i++) {
            GPHardCaseArchive.Case c = hardCases.get(i);
            p.setProperty("hard." + i + ".map", Integer.toString(c.mapIndex));
            p.setProperty("hard." + i + ".opponent", c.opponentName);
            p.setProperty("hard." + i + ".pinned", Boolean.toString(c.pinned));
        }

        List<double[]> novelty = population.noveltySnapshot();
        p.setProperty("novelty.count", Integer.toString(novelty.size()));
        for (i = 0; i < novelty.size(); i++) p.setProperty("novelty." + i, encodeVector(novelty.get(i)));

        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName().toString() + ".tmp");
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
        if (!"1".equals(p.getProperty("version"))) {
            throw new IOException("Unsupported checkpoint version: " + p.getProperty("version"));
        }
        requireEqual("maps", p.getProperty("config.maps"), String.join(",", cfg.maps));
        requireEqual("opponents", p.getProperty("config.opponents"), String.join(",", cfg.opponents));
        requireEqual("unit type table", p.getProperty("config.utt"), Integer.toString(cfg.unitTypeTableVersion));
        requireEqual("conflict policy", p.getProperty("config.conflict"), Integer.toString(cfg.conflictPolicy));
        requireEqual("evaluation seed", p.getProperty("config.evaluationSeed"), Long.toString(cfg.evaluationSeed));
        requireEqual("max cycles", p.getProperty("config.maxCycles"), Integer.toString(cfg.maxCycles));
        requireEqual("inactive cycle limit", p.getProperty("config.maxInactiveCycles"), Integer.toString(cfg.maxInactiveCycles));
        requireEqual("novelty setting", p.getProperty("config.novelty"), Boolean.toString(cfg.useNoveltyBonus));
        requireEqual("hard-case setting", p.getProperty("config.hardCases"), Boolean.toString(cfg.useHardCaseArchive));
        Random random = decodeRandom(p.getProperty("random"));
        GPPopulation population = new GPPopulation(cfg, utt, random);

        List<GPIndividual> individuals = new ArrayList<>();
        int count = integer(p, "individual.count");
        for (int i = 0; i < count; i++) {
            individuals.add(new GPIndividual(GPSExpression.parseAction(required(p, "individual." + i))));
        }

        Map<String, Double> elo = new HashMap<>();
        count = integer(p, "elo.count");
        for (int i = 0; i < count; i++) {
            elo.put(required(p, "elo." + i + ".key"),
                    Double.parseDouble(required(p, "elo." + i + ".rating")));
        }

        List<GPHardCaseArchive.Case> hardCases = new ArrayList<>();
        count = integer(p, "hard.count");
        for (int i = 0; i < count; i++) {
            hardCases.add(new GPHardCaseArchive.Case(integer(p, "hard." + i + ".map"),
                    required(p, "hard." + i + ".opponent"),
                    Boolean.parseBoolean(p.getProperty("hard." + i + ".pinned", "false"))));
        }

        List<double[]> novelty = new ArrayList<>();
        count = integer(p, "novelty.count");
        for (int i = 0; i < count; i++) novelty.add(decodeVector(required(p, "novelty." + i)));

        population.restoreState(individuals, integer(p, "generation"), elo, hardCases, novelty,
                Boolean.parseBoolean(p.getProperty("curriculum.completed", "false")));
        String generalistExpression = p.getProperty("generalist.champion");
        if (generalistExpression != null) {
            population.setGeneralistChampion(
                    new GPIndividual(GPSExpression.parseAction(generalistExpression)));
        }
        String curriculumExpression = p.getProperty("curriculum.champion");
        if (curriculumExpression != null) {
            population.setCurriculumChampion(
                    new GPIndividual(GPSExpression.parseAction(curriculumExpression)));
        }
        count = Integer.parseInt(p.getProperty("seeded.count", "0"));
        for (int i = 0; i < count; i++) {
            population.addSeededSpecialist(
                    new GPIndividual(GPSExpression.parseAction(required(p, "seeded." + i))), 0);
        }
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
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            return (Random) in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Cannot restore checkpoint RNG", e);
        }
    }

    private static String encodeVector(double[] vector) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) out.append(',');
            out.append(vector[i]);
        }
        return out.toString();
    }

    private static double[] decodeVector(String encoded) {
        if (encoded.isEmpty()) return new double[0];
        String[] values = encoded.split(",");
        double[] vector = new double[values.length];
        for (int i = 0; i < values.length; i++) vector[i] = Double.parseDouble(values[i]);
        return vector;
    }

    private static int integer(Properties p, String key) throws IOException {
        return Integer.parseInt(required(p, key));
    }

    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null) throw new IOException("Missing checkpoint property: " + key);
        return value;
    }

    private static void requireEqual(String label, String saved, String requested) throws IOException {
        if (saved == null) return;
        if (!requested.equals(saved)) {
            throw new IOException("Checkpoint " + label + " differs from the requested configuration");
        }
    }
}
