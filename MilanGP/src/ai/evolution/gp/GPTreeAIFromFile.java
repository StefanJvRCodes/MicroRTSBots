package ai.evolution.gp;

import rts.units.UnitTypeTable;

import java.io.IOException;

public class GPTreeAIFromFile extends GPTreeAI {
    private static final String DEFAULT_BOT_FILE = "./models/best_v3.txt";

    public GPTreeAIFromFile(UnitTypeTable utt) throws IOException {
        super(utt, System.getProperty("gp.botFile", DEFAULT_BOT_FILE));
    }
}
