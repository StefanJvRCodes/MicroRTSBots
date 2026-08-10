package ai.evolution.gp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GPHardCaseArchive {
    public static class Case {
        public final int mapIndex;
        public final String opponentName;
        public final boolean pinned;

        public Case(int mapIndex, String opponentName) {
            this(mapIndex, opponentName, false);
        }

        public Case(int mapIndex, String opponentName, boolean pinned) {
            this.mapIndex = mapIndex;
            this.opponentName = opponentName;
            this.pinned = pinned;
        }

        public String key() { return opponentName + "@map" + mapIndex; }
    }

    private final List<Case> cases = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private final int maxSize;

    public GPHardCaseArchive(int maxSize) {
        this.maxSize = maxSize;
    }

    public List<Case> cases() {
        return Collections.unmodifiableList(cases);
    }

    public boolean add(int mapIndex, String opponentName) {
        return addInternal(mapIndex, opponentName, false);
    }

    public boolean addPinned(int mapIndex, String opponentName) {
        return addInternal(mapIndex, opponentName, true);
    }

    private boolean addInternal(int mapIndex, String opponentName, boolean pinned) {
        if (maxSize <= 0) return false;
        Case c = new Case(mapIndex, opponentName, pinned);
        if (keys.contains(c.key())) {
            if (!pinned) return false;
            for (int i = 0; i < cases.size(); i++) {
                Case existing = cases.get(i);
                if (existing.key().equals(c.key()) && !existing.pinned) {
                    cases.set(i, c);
                    return true;
                }
            }
            return false;
        }
        keys.add(c.key());
        cases.add(c);
        if (cases.size() > maxSize) {
            int evictionIndex = -1;
            for (int i = 0; i < cases.size(); i++) {
                if (!cases.get(i).pinned) {
                    evictionIndex = i;
                    break;
                }
            }
            if (evictionIndex >= 0) {
                Case evicted = cases.remove(evictionIndex);
                keys.remove(evicted.key());
            }
        }
        return keys.contains(c.key());
    }

    public boolean remove(int mapIndex, String opponentName) {
        String key = new Case(mapIndex, opponentName).key();
        if (!keys.remove(key)) return false;
        cases.removeIf(c -> c.key().equals(key));
        return true;
    }

    public int size() { return cases.size(); }

    public void restore(List<Case> restored) {
        cases.clear();
        keys.clear();
        for (Case c : restored) {
            if (c.pinned) addPinned(c.mapIndex, c.opponentName);
            else add(c.mapIndex, c.opponentName);
        }
    }
}
