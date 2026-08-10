package ai.evolution.gp.nodes;

import java.util.Random;

public interface PerturbableTerminal {
    GPNode perturb(Random rnd);
}
