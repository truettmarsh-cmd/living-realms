package livingrealms.client.ui;

/**
 * How the City Manager's page tabs fit the sidebar: a list of named tabs, 16 to 21 pixels a row, while they fit;
 * otherwise a grid of icons (the name shows on hover), with as many columns as it takes, so any number of pages fits
 * any window the screen allows.
 */
record TabLayout(int cols, int cellW, int pitch) {
    static final int MIN_ROW = 16, MAX_ROW = 21, MIN_CELL = 18;

    boolean grid() { return cols > 1; }

    static TabLayout of(int n, int width, int height) {
        n = Math.max(1, n);
        if (n * MIN_ROW <= height) return new TabLayout(1, width, Math.min(MAX_ROW, height / n));
        // icon squares about as wide as they are tall, more columns only if the rows still do not fit
        for (int cols = Math.max(2, width / 25); ; cols++) {
            int rows = (n + cols - 1) / cols, cell = width / cols;
            if (rows * MIN_CELL <= height || cell <= MIN_CELL + 2) return new TabLayout(cols, cell, Math.max(MIN_CELL, Math.min(22, height / rows)));
        }
    }
}
