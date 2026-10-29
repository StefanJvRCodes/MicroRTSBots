package ai.custom.adapter;

import java.io.Serializable;
import java.util.Random;

/**
 * Fully connected network: ReLU hidden layers, linear output. Small enough
 * that forward and backward passes are written out by hand, so the adapter
 * needs no deep-learning library.
 *
 * <p>{@link #forward} only reads the weights, so many games can run it in
 * parallel; gradients go into a separate {@link Grads} per thread and the
 * weights change only in {@link Adam#step}, single-threaded.
 */
public final class Mlp implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Layer widths, input first: {in, hidden..., out}. */
    final int[] sizes;
    /** weights[l][o * in + i]: layer l, output o, input i. */
    final double[][] weights;
    final double[][] biases;

    /**
     * He-initialized hidden layers; output weights scaled by {@code outputGain}
     * so a policy head can start near zero (0.01, as in common PPO setups -
     * here that means the adapter starts out leaving the base policy as is).
     */
    public Mlp(int[] sizes, double outputGain, Random random) {
        this.sizes = sizes.clone();
        int layers = sizes.length - 1;
        weights = new double[layers][];
        biases = new double[layers][];
        for (int l = 0; l < layers; l++) {
            int in = sizes[l];
            int out = sizes[l + 1];
            weights[l] = new double[in * out];
            biases[l] = new double[out];
            double std = (l == layers - 1) ? outputGain / Math.sqrt(in) : Math.sqrt(2.0 / in);
            for (int k = 0; k < weights[l].length; k++) {
                weights[l][k] = random.nextGaussian() * std;
            }
        }
    }

    public int inputSize() {
        return sizes[0];
    }

    public int outputSize() {
        return sizes[sizes.length - 1];
    }

    /** All layer activations, input first and output last - what {@link #backward} needs. */
    public double[][] forward(double[] input) {
        if (input.length != sizes[0]) {
            throw new IllegalArgumentException("Expected " + sizes[0] + " inputs, got " + input.length);
        }
        int layers = sizes.length - 1;
        double[][] activations = new double[layers + 1][];
        activations[0] = input;
        for (int l = 0; l < layers; l++) {
            int in = sizes[l];
            int out = sizes[l + 1];
            double[] x = activations[l];
            double[] w = weights[l];
            double[] y = new double[out];
            for (int o = 0; o < out; o++) {
                double sum = biases[l][o];
                int row = o * in;
                for (int i = 0; i < in; i++) {
                    sum += w[row + i] * x[i];
                }
                y[o] = (l < layers - 1 && sum < 0) ? 0.0 : sum;
            }
            activations[l + 1] = y;
        }
        return activations;
    }

    public double[] output(double[] input) {
        double[][] activations = forward(input);
        return activations[activations.length - 1];
    }

    /** Adds d(loss)/d(parameters) to {@code grads}, given d(loss)/d(output) for the pass that produced {@code activations}. */
    public void backward(double[][] activations, double[] outputGrad, Grads grads) {
        int layers = sizes.length - 1;
        double[] delta = outputGrad;
        for (int l = layers - 1; l >= 0; l--) {
            int in = sizes[l];
            int out = sizes[l + 1];
            double[] x = activations[l];
            double[] w = weights[l];
            double[] gw = grads.weights[l];
            double[] gb = grads.biases[l];
            for (int o = 0; o < out; o++) {
                double d = delta[o];
                if (d == 0.0) {
                    continue;
                }
                gb[o] += d;
                int row = o * in;
                for (int i = 0; i < in; i++) {
                    gw[row + i] += d * x[i];
                }
            }
            if (l == 0) {
                break;
            }
            double[] previous = new double[in];
            for (int o = 0; o < out; o++) {
                double d = delta[o];
                if (d == 0.0) {
                    continue;
                }
                int row = o * in;
                for (int i = 0; i < in; i++) {
                    previous[i] += w[row + i] * d;
                }
            }
            for (int i = 0; i < in; i++) {
                if (x[i] <= 0.0) {
                    previous[i] = 0.0; // ReLU: x is this hidden layer's output
                }
            }
            delta = previous;
        }
    }

    public Grads newGrads() {
        return new Grads(this);
    }

    /** Gradient buffers shaped like one network's parameters. */
    public static final class Grads {
        final double[][] weights;
        final double[][] biases;

        Grads(Mlp net) {
            weights = new double[net.weights.length][];
            biases = new double[net.biases.length][];
            for (int l = 0; l < weights.length; l++) {
                weights[l] = new double[net.weights[l].length];
                biases[l] = new double[net.biases[l].length];
            }
        }

        public void add(Grads other) {
            for (int l = 0; l < weights.length; l++) {
                addInto(weights[l], other.weights[l]);
                addInto(biases[l], other.biases[l]);
            }
        }

        public void scale(double factor) {
            for (int l = 0; l < weights.length; l++) {
                scaleInPlace(weights[l], factor);
                scaleInPlace(biases[l], factor);
            }
        }

        public double squaredNorm() {
            double sum = 0.0;
            for (int l = 0; l < weights.length; l++) {
                sum += squaredSum(weights[l]) + squaredSum(biases[l]);
            }
            return sum;
        }

        private static void addInto(double[] target, double[] source) {
            for (int i = 0; i < target.length; i++) {
                target[i] += source[i];
            }
        }

        private static void scaleInPlace(double[] values, double factor) {
            for (int i = 0; i < values.length; i++) {
                values[i] *= factor;
            }
        }

        private static double squaredSum(double[] values) {
            double sum = 0.0;
            for (double v : values) {
                sum += v * v;
            }
            return sum;
        }
    }

    /** Adam optimizer state for one network (eps 1e-5, as in common PPO implementations). */
    public static final class Adam implements Serializable {
        private static final long serialVersionUID = 1L;
        private static final double BETA1 = 0.9;
        private static final double BETA2 = 0.999;
        private static final double EPSILON = 1e-5;

        private final double[][] mWeights;
        private final double[][] vWeights;
        private final double[][] mBiases;
        private final double[][] vBiases;
        private long steps = 0;

        public Adam(Mlp net) {
            int layers = net.weights.length;
            mWeights = new double[layers][];
            vWeights = new double[layers][];
            mBiases = new double[layers][];
            vBiases = new double[layers][];
            for (int l = 0; l < layers; l++) {
                mWeights[l] = new double[net.weights[l].length];
                vWeights[l] = new double[net.weights[l].length];
                mBiases[l] = new double[net.biases[l].length];
                vBiases[l] = new double[net.biases[l].length];
            }
        }

        public void step(Mlp net, Grads grads, double learningRate) {
            steps++;
            double correction1 = 1.0 - Math.pow(BETA1, steps);
            double correction2 = 1.0 - Math.pow(BETA2, steps);
            for (int l = 0; l < net.weights.length; l++) {
                update(net.weights[l], grads.weights[l], mWeights[l], vWeights[l], learningRate, correction1, correction2);
                update(net.biases[l], grads.biases[l], mBiases[l], vBiases[l], learningRate, correction1, correction2);
            }
        }

        private static void update(double[] params, double[] grad, double[] m, double[] v, double learningRate,
                                   double correction1, double correction2) {
            for (int i = 0; i < params.length; i++) {
                double g = grad[i];
                m[i] = BETA1 * m[i] + (1 - BETA1) * g;
                v[i] = BETA2 * v[i] + (1 - BETA2) * g * g;
                params[i] -= learningRate * (m[i] / correction1) / (Math.sqrt(v[i] / correction2) + EPSILON);
            }
        }
    }
}
