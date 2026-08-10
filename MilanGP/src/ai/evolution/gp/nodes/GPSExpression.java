package ai.evolution.gp.nodes;

import java.util.ArrayList;
import java.util.List;

public class GPSExpression {
    private GPSExpression() {}

    public static String write(GPNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private static void write(GPNode node, StringBuilder sb) {
        sb.append('(').append(node.getName());
        for (String p : node.getParams()) sb.append(' ').append(p);
        for (GPNode c : node.getChildren()) { sb.append(' '); write(c, sb); }
        sb.append(')');
    }

    public static ActionNode parseAction(String expression) {
        return (ActionNode) parse(expression);
    }

    public static GPNode parse(String expression) {
        List<String> tokens = tokenize(expression);
        int[] pos = {0};
        GPNode result = parseTokens(tokens, pos);
        if (pos[0] != tokens.size()) throw new IllegalArgumentException("Unexpected trailing tokens in: " + expression);
        return result;
    }

    private static List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')') {
                if (cur.length() > 0) { tokens.add(cur.toString()); cur.setLength(0); }
                tokens.add(String.valueOf(c));
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { tokens.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) tokens.add(cur.toString());
        return tokens;
    }

    private static GPNode parseTokens(List<String> tokens, int[] pos) {
        if (!tokens.get(pos[0]).equals("(")) throw new IllegalArgumentException("Expected '(' at token " + pos[0]);
        pos[0]++;
        String name = tokens.get(pos[0]);
        pos[0]++;

        List<String> params = new ArrayList<>();
        List<GPNode> children = new ArrayList<>();
        while (!tokens.get(pos[0]).equals(")")) {
            if (tokens.get(pos[0]).equals("(")) {
                children.add(parseTokens(tokens, pos));
            } else {
                params.add(tokens.get(pos[0]));
                pos[0]++;
            }
        }
        pos[0]++;
        return GPNodeRegistry.build(name, children, params);
    }
}
