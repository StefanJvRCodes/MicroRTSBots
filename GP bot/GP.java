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
    public double modiRate = -1.0;
    public int numOutputCells = 3; //TODO: Figure out how many actions you want
    public int currSeed;
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
                        case "modi rate":
                            modiRate = Double.parseDouble(value);
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
    
        Node child = parent1.deepCopy();
        if (child == null || parent2 == null) {
            return child;
        }

    
        Node targetInChild = child.getRandomNode(random);
        boolean isRoot = (targetInChild == child);

        if (!isRoot) {
            Node donor = parent2.getRandomNode(random);
            Node donorCopy = donor.deepCopy();
            Node parentOfTarget = child.findParentOf(targetInChild);
            parentOfTarget.replaceChild(targetInChild, donorCopy);
            return child;
        }

        for (int attempt = 0; attempt < 10; attempt++) {
            Node donor = parent2.getRandomNode(random);
            if (donor.type == Node.NodeType.FUNCTION) {
                Node donorCopy = donor.deepCopy();
                if (donorCopy.isModi){
                    return donorCopy;
                }
                donorCopy.isModi = true;
                if (donorCopy.outputCellIndex < 0) {
                    donorCopy.outputCellIndex = random.nextInt(numOutputCells);
                }
                return donorCopy;
            }
        }
        return child;
    }


    //TODO: double check mutation
    public Node mutate(Node parent) {
        if (parent == null) {
            throw new IllegalArgumentException("Parent cannot be null");
        }

        Node child = parent.deepCopy();
        Node target = child.getRandomNode(random);
        boolean isRoot = (target == child);

        int remainingDepth = Math.max(1, maxDepth - target.getDepth() + 1);

        Node replacement;
        if (isRoot) {
            // Root must end up as a FUNCTION node (rule 1/2 as above) — force
            // "full" growth and at least depth 2 so it can't hand back a terminal.
            replacement = Node.generateGrow(random, functions, terminals, featureIndices,
                    Math.max(2, remainingDepth));
            Node.configureModiNodes(replacement, modiRate, numOutputCells, random); // forces its root Modi too
            return replacement;
        }

        replacement = random.nextBoolean()
                ? Node.generateFull(random, functions, terminals, featureIndices, remainingDepth)
                : Node.generateGrow(random, functions, terminals, featureIndices, remainingDepth);

        // This is a plain subtree, not a whole tree, so don't use
        // configureModiNodes here — it always forces its own root to be Modi,
        // which is only correct for the actual tree root. Roll Modi status for
        // the new subtree's nodes the ordinary (non-forced) way instead.
        rollModiForSubtree(replacement, modiRate, numOutputCells, random);

        Node parentOfTarget = child.findParentOf(target);
        parentOfTarget.replaceChild(target, replacement);
        return child;
    }

    // Assigns Modi status to every node in a freshly generated subtree using
    // the plain probability µ, with no forced-root exception (unlike
    // Node.configureModiNodes, which is only for the whole tree's root).
    private void rollModiForSubtree(Node node, double modiRate, int numOutputCells, Random random) {
        if (node.type == Node.NodeType.TERMINAL) {
            node.isModi = false;
            return;
        }
        node.isModi = random.nextDouble() < modiRate;
        node.outputCellIndex = node.isModi ? random.nextInt(numOutputCells) : -1;
        if (node.children != null) {
            for (Node c : node.children) {
                if (c != null) rollModiForSubtree(c, modiRate, numOutputCells, random);
            }
        }
    }



    /*evaluation
        //start tournament for first individual
        //for every player action, set feature vector
        //get action probability distribution and pick best action
        //after tournament, store win rate


        set feature vector
        //public double evaluate(double[] features, OutputVector output)

        features[0] = Math.min(enemyCount, 10) / 10.0; // Cap at 10 enemies
        features[1] = encodeEnemyType(nearestEnemyType); // 0.0-1.0
        features[2] = Math.min(allyCount, 5) / 5.0; // Cap at 5 allies
        features[3] = encodeUnitType(myType); // 0.0-1.0
        features[4] = Math.min(resources, 200) / 200.0; // Cap resources
        features[5] = myHealth / maxHealth;  // Should I fight or flee?
        features[6] = myDamage / maxEnemyHealth;  // Can I kill them quickly?
        features[7] = alliesNearResource / enemiesNearResource;
    
    */

}
