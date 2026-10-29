package ai.custom.adapter;

import ai.custom.GP_bot.TreePolicy;
import rts.GameState;
import rts.PhysicalGameState;
import rts.units.Unit;
import rts.units.UnitType;

/**
 * What the adapter sees. The policy input is per unit - the adapter only
 * reweights that unit's {@value TreePolicy#NUM_OUTPUT_CELLS} output cells -
 * and the value input summarizes the whole map for PPO's critic.
 *
 * <p>Policy input, per unit: the tree's own 7 features, the base's choice
 * (one-hot) and which cells apply, the unit's type, health and cargo, its
 * position, map size and game time, both players' resources, the offset to
 * the closest enemy and resource, and an egocentric square patch of the map
 * (8 channels: wall/off-map, resource, own worker/combat/building, enemy
 * worker/combat/building).
 *
 * <p>Value input: the map pooled into {@value #VALUE_GRID}x{@value #VALUE_GRID}
 * regions (resources and unit counts per side and category) plus the same
 * global numbers.
 */
public final class AdapterObservation {
    public static final int CELLS = TreePolicy.NUM_OUTPUT_CELLS;
    private static final int PATCH_CHANNELS = 8;
    private static final int UNIT_SCALARS = 42;
    static final int VALUE_GRID = 8;
    private static final int VALUE_CHANNELS = 7;
    private static final int GLOBAL_SCALARS = 11;

    // Cell codes in the per-state grid; code - 1 is the patch channel.
    private static final int EMPTY = 0;
    private static final int WALL = 1;
    private static final int RESOURCE = 2;
    private static final int OWN = 3;    // + category (worker 0, combat 1, building 2)
    private static final int ENEMY = 6;  // + category

    private AdapterObservation() {
    }

    public static int unitSize(int patchRadius) {
        int side = 2 * patchRadius + 1;
        return UNIT_SCALARS + side * side * PATCH_CHANNELS;
    }

    public static int valueSize() {
        return VALUE_GRID * VALUE_GRID * VALUE_CHANNELS + GLOBAL_SCALARS;
    }

    /** 0 worker, 1 combat (light/heavy/ranged), 2 building (base/barracks), -1 resource or other. */
    static int category(UnitType type) {
        if (type.isResource) {
            return -1;
        }
        if ("Worker".equals(type.name)) {
            return 0;
        }
        if (type.canMove) {
            return 1;
        }
        return 2;
    }

    /** One state's map as cell codes, built once per decision step and shared by every unit's observation. */
    public static final class Grid {
        final int width;
        final int height;
        final int[] codes;
        final int player;
        final int cycle;
        final int ownResources;
        final int enemyResources;
        final int[] ownCounts = new int[3];
        final int[] enemyCounts = new int[3];
        final GameState gs;

        public Grid(GameState gs, int player) {
            PhysicalGameState pgs = gs.getPhysicalGameState();
            this.gs = gs;
            this.width = pgs.getWidth();
            this.height = pgs.getHeight();
            this.player = player;
            this.cycle = gs.getTime();
            this.codes = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    codes[y * width + x] = pgs.getTerrain(x, y) == PhysicalGameState.TERRAIN_WALL ? WALL : EMPTY;
                }
            }
            for (Unit u : pgs.getUnits()) {
                int category = category(u.getType());
                int code;
                if (category < 0) {
                    code = RESOURCE;
                } else if (u.getPlayer() == player) {
                    code = OWN + category;
                    ownCounts[category]++;
                } else {
                    code = ENEMY + category;
                    enemyCounts[category]++;
                }
                codes[u.getY() * width + u.getX()] = code;
            }
            this.ownResources = gs.getPlayer(player).getResources();
            this.enemyResources = gs.getPlayer(1 - player).getResources();
        }

        int code(int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) {
                return WALL;
            }
            return codes[y * width + x];
        }
    }

    public static double[] unit(Unit unit, Grid grid, TreePolicy.UnitDecision base, int patchRadius, int maxCycles) {
        double[] obs = new double[unitSize(patchRadius)];
        int k = 0;

        double[] treeFeatures = TreePolicy.extractFeatures(unit, grid.gs, grid.player);
        for (double f : treeFeatures) {
            obs[k++] = f;
        }
        for (int cell = 0; cell < CELLS; cell++) {
            obs[k++] = cell == base.chosenCell ? 1.0 : 0.0;
        }
        for (int cell = 0; cell < CELLS; cell++) {
            obs[k++] = base.candidates[cell] != null ? 1.0 : 0.0;
        }

        UnitType type = unit.getType();
        String[] typeNames = {"Worker", "Light", "Heavy", "Ranged", "Base", "Barracks"};
        for (String name : typeNames) {
            obs[k++] = name.equals(type.name) ? 1.0 : 0.0;
        }
        obs[k++] = type.hp > 0 ? unit.getHitPoints() / (double) type.hp : 0.0;
        obs[k++] = unit.getResources() > 0 ? 1.0 : 0.0;
        obs[k++] = grid.width > 1 ? unit.getX() / (double) (grid.width - 1) : 0.0;
        obs[k++] = grid.height > 1 ? unit.getY() / (double) (grid.height - 1) : 0.0;
        obs[k++] = grid.width / 32.0;
        obs[k++] = grid.height / 32.0;
        obs[k++] = Math.min(1.0, grid.cycle / (double) Math.max(1, maxCycles));
        obs[k++] = Math.min(grid.ownResources, 20) / 20.0;
        obs[k++] = Math.min(grid.enemyResources, 20) / 20.0;
        k = putOffset(obs, k, unit, TreePolicy.findClosestEnemy(unit, grid.gs), grid);
        k = putOffset(obs, k, unit, TreePolicy.findClosestResource(unit, grid.gs), grid);

        for (int dy = -patchRadius; dy <= patchRadius; dy++) {
            for (int dx = -patchRadius; dx <= patchRadius; dx++) {
                int code = grid.code(unit.getX() + dx, unit.getY() + dy);
                if (code != EMPTY) {
                    obs[k + code - 1] = 1.0;
                }
                k += PATCH_CHANNELS;
            }
        }
        return obs;
    }

    private static int putOffset(double[] obs, int k, Unit unit, Unit target, Grid grid) {
        if (target == null) {
            obs[k++] = 0.0;
            obs[k++] = 0.0;
            obs[k++] = 1.0;
            return k;
        }
        int dx = target.getX() - unit.getX();
        int dy = target.getY() - unit.getY();
        obs[k++] = dx / (double) grid.width;
        obs[k++] = dy / (double) grid.height;
        obs[k++] = (Math.abs(dx) + Math.abs(dy)) / (double) (grid.width + grid.height);
        return k;
    }

    public static double[] value(Grid grid, int maxCycles) {
        double[] obs = new double[valueSize()];
        int regions = VALUE_GRID * VALUE_GRID;
        double[] resourceAmount = new double[regions];
        int[][] counts = new int[6][regions];
        for (Unit u : grid.gs.getPhysicalGameState().getUnits()) {
            int region = (u.getY() * VALUE_GRID / grid.height) * VALUE_GRID + (u.getX() * VALUE_GRID / grid.width);
            int category = category(u.getType());
            if (category < 0) {
                resourceAmount[region] += u.getResources();
            } else {
                counts[(u.getPlayer() == grid.player ? 0 : 3) + category][region]++;
            }
        }
        int k = 0;
        for (int r = 0; r < regions; r++) {
            obs[k++] = Math.min(resourceAmount[r], 40) / 40.0;
            for (int c = 0; c < 6; c++) {
                obs[k++] = Math.min(counts[c][r], 4) / 4.0;
            }
        }
        obs[k++] = Math.min(grid.ownResources, 20) / 20.0;
        obs[k++] = Math.min(grid.enemyResources, 20) / 20.0;
        for (int c = 0; c < 3; c++) {
            obs[k++] = Math.min(grid.ownCounts[c], 20) / 20.0;
        }
        for (int c = 0; c < 3; c++) {
            obs[k++] = Math.min(grid.enemyCounts[c], 20) / 20.0;
        }
        obs[k++] = grid.width / 32.0;
        obs[k++] = grid.height / 32.0;
        obs[k++] = Math.min(1.0, grid.cycle / (double) Math.max(1, maxCycles));
        return obs;
    }
}
