package ai.evolution.gp;

import rts.units.UnitTypeTable;

import java.io.IOException;

public class StructuredGPAIFromFile extends StructuredGPAI {
    private static final String DEFAULT_BOT_FILE = "./models/best.txt";

    public StructuredGPAIFromFile(UnitTypeTable utt) throws IOException {
        super(utt, System.getProperty("gp.botFile", DEFAULT_BOT_FILE));
    }
}
