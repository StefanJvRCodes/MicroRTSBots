package ai.custom.GP_bot;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain-text table with aligned columns, for evaluation reports printed to
 * the console and saved next to the results. Text columns are left aligned,
 * columns whose cells all look numeric are right aligned; a null row is a
 * separator line.
 */
public final class TextTable {
    private final String[] header;
    private final List<String[]> rows = new ArrayList<>();

    public TextTable(String... header) {
        this.header = header;
    }

    public TextTable row(String... cells) {
        if (cells.length != header.length) {
            throw new IllegalArgumentException("Expected " + header.length + " cells, got " + cells.length);
        }
        rows.add(cells);
        return this;
    }

    public TextTable separator() {
        rows.add(null);
        return this;
    }

    public String render() {
        int columns = header.length;
        int[] width = new int[columns];
        boolean[] numeric = new boolean[columns];
        for (int c = 0; c < columns; c++) {
            width[c] = header[c].length();
            numeric[c] = true;
        }
        for (String[] r : rows) {
            if (r == null) {
                continue;
            }
            for (int c = 0; c < columns; c++) {
                width[c] = Math.max(width[c], r[c].length());
                if (!r[c].isEmpty() && !r[c].matches("[-+0-9.,/%± ]+")) {
                    numeric[c] = false;
                }
            }
        }
        StringBuilder out = new StringBuilder();
        appendLine(out, header, width, numeric);
        appendRule(out, width);
        for (String[] r : rows) {
            if (r == null) {
                appendRule(out, width);
            } else {
                appendLine(out, r, width, numeric);
            }
        }
        return out.toString();
    }

    private static void appendLine(StringBuilder out, String[] cells, int[] width, boolean[] numeric) {
        for (int c = 0; c < cells.length; c++) {
            if (c > 0) {
                out.append("  ");
            }
            String cell = cells[c];
            String pad = " ".repeat(width[c] - cell.length());
            out.append(numeric[c] ? pad + cell : cell + pad);
        }
        out.append(System.lineSeparator());
    }

    private static void appendRule(StringBuilder out, int[] width) {
        for (int c = 0; c < width.length; c++) {
            if (c > 0) {
                out.append("  ");
            }
            out.append("-".repeat(width[c]));
        }
        out.append(System.lineSeparator());
    }
}
