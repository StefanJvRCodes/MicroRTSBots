import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Vector;

import Node.NodeType;
import java.lang.classfile.components.ClassPrinter;

import java.util.Random;

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

    public Random random;


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


            random = new Random(seed);




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



    public Node crossover(Node parent1, Node parent2) {
        if (parent1 == null || parent2 == null) {
            throw new IllegalArgumentException("Parents cannot be null");
        }

        Node child = parent1.deepCopy();
        Node targetInChild = child.getRandomNode(random);
        if (targetInChild == null) return child;

        Node donor;
        if (targetInChild.type == Node.NodeType.ACTION_HEAD) {
            Node donorNode = parent2.getRandomNodeFromActionSubtree(targetInChild);
            donor = (donorNode == null) ? null : donorNode.getActionHead();
        } else {
            Node actionHead = targetInChild.getActionHead();
            donor = (actionHead == null) ? null
                    : parent2.getRandomNodeFromActionSubtree(actionHead);
        }
        if (donor == null) return child;

        Node donorCopy = donor.deepCopy();
        child = child.replaceSubtree(targetInChild, donorCopy);
        return child;
    }


    public Node mutate(Node parent) {
        if (parent == null) {
            throw new IllegalArgumentException("Parent cannot be null");
        }

        Node child = parent.deepCopy();
        Node targetInChild = child.getRandomNode(random);
        if (targetInChild == null) return child;

        Node newSubtree;
        if (targetInChild.type == Node.NodeType.ACTION_HEAD) {
            newSubtree = Node.generateRandomActionSubtree(random, maxDepth, targetInChild.actionType);
        } else {
            newSubtree = Node.generateRandomSubtree(random, maxDepth);
        }

        child = child.replaceSubtree(targetInChild, newSubtree);
        return child;
    }


}
