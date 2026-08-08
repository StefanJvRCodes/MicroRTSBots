import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Vector;


//TODO: decide terminal set
//TODO: decide function set
//TODO: get winrates


public class GP {
    //GP parameters
    public int seed = -1;
    public int populationSize = -1;
    public int maxDepth = -1;
    public int functionProbability = -1;
    public int terminalProbability = -1;
    public int tournamentSize = -1;
    public int mutationRate = -1;
    public int crossoverRate = -1;
    public int reproductionRate = -1;
    public int generations = -1;


    //outputs
    public Node[] population;
    public double[] winRates;
    public Vector<Double>[] actionDistributions;




    GP(){
        String fileName = "parameters.txt";

        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    String parameter = parts[0].trim();
                    String value = parts[1].trim();
                    parameter = parameter.toLowerCase();

                    switch (parameter) {
                        case "seed":
                            seed = Integer.parseInt(value);
                            break;
                        case "population size":
                            populationSize = Integer.parseInt(value);
                            break;
                        case "max depth":
                            maxDepth = Integer.parseInt(value);
                            break;
                        case "function probability":
                            functionProbability = Integer.parseInt(value);
                            break;
                        case "terminal probability":
                            terminalProbability = Integer.parseInt(value);
                            break;
                        case "tournament size":
                            tournamentSize = Integer.parseInt(value);
                            break;
                        case "mutation rate":
                            mutationRate = Integer.parseInt(value);
                            break;
                        case "crossover rate":
                            crossoverRate = Integer.parseInt(value);
                            break;
                        case "reproduction rate":
                            reproductionRate = Integer.parseInt(value);
                            break;
                        case "generations":
                            generations = Integer.parseInt(value);
                            break;
                        default:
                            System.out.println("Unknown parameter: " + parameter);
                    }
                    if (mutationRate + crossoverRate + reproductionRate != 100) {
                        throw new IllegalArgumentException("Mutation, crossover, and reproduction rates must sum to 100.");
                    }
                    if (functionProbability + terminalProbability != 100) {
                        throw new IllegalArgumentException("Function and terminal probabilities must sum to 100.");
                    }
                }
            }
            if (seed == -1 || populationSize == -1 || maxDepth == -1 || functionProbability == -1 || terminalProbability == -1 || tournamentSize == -1 || mutationRate == -1 || crossoverRate == -1 || reproductionRate == -1 || generations == -1) {
                throw new IllegalArgumentException("One or more parameters are missing in the parameters file.");
            }


        }
        catch (Exception e) {
            System.out.println("Error reading parameters file: " + e.getMessage());
        }

    }


    public void run(){
        //TODO: implement GP algorithm
        //initialize population
        //Start
        //evaluate population
        //select and breed
        //goto start
    }

}
