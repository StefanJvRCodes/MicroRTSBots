import java.util.Vector;

public class TrainingCase {
    public double[][] xBlock;
    public double targetY;
    public Vector<String> dates;

    public TrainingCase(Vector<double[]> xValues, double targetY, Vector<String> dates) {
        this.targetY = targetY;
        this.xBlock = new double[xValues == null ? 0 : xValues.size()][];
        if (xValues != null) {
            for (int i = 0; i < xValues.size(); i++) {
                double[] row = xValues.get(i);
                this.xBlock[i] = row == null ? null : row.clone();
            }
        }

        if (dates != null) {
            this.dates = new Vector<>(dates);
        } else {
            this.dates = new Vector<>();
        }
    }



    public double[][] getTrainingArray() {
        double[][] trainingArray = new double[xBlock.length][];
        for (int i = 0; i < xBlock.length; i++) {
            trainingArray[i] = xBlock[i] == null ? null : xBlock[i].clone();
        }
        return trainingArray;
    }
}
