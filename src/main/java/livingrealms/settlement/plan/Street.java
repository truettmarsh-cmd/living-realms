package livingrealms.settlement.plan;

import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** A straight street of the town skeleton: it starts on its parent street and runs one way along an axis. */
public final class Street {
    public final int id;
    public final RoadClass cls;
    /** Centre-line start column, unit direction and length (columns 0..length). */
    public final int ax, az;
    public final Direction dir;
    public final int length;
    /** The street it branches from (-1 for the market place) and how far along that street it starts. */
    public final int parent, parentAt;

    public Street(int id, RoadClass cls, int ax, int az, Direction dir, int length, int parent, int parentAt) {
        this.id = id; this.cls = cls; this.ax = ax; this.az = az; this.dir = dir; this.length = length;
        this.parent = parent; this.parentAt = parentAt;
    }

    public int x(int t) { return ax + dir.getStepX() * t; }
    public int z(int t) { return az + dir.getStepZ() * t; }
    public boolean alongX() { return dir.getAxis() == Direction.Axis.X; }
    public int half() { return cls.width / 2; }

    /** Paved columns from along-distance t0 to t1 (inclusive), the full width. */
    public BoundingBox corridor(int t0, int t1) {
        int h = half(), lo = cls.width - 1 - h;
        int x0 = x(t0), x1 = x(t1), z0 = z(t0), z1 = z(t1);
        if (alongX()) return new BoundingBox(Math.min(x0, x1), 0, z0 - h, Math.max(x0, x1), 0, z0 + lo);
        return new BoundingBox(x0 - h, 0, Math.min(z0, z1), x0 + lo, 0, Math.max(z0, z1));
    }

    public BoundingBox corridor() { return corridor(0, length); }

    /** Along-distance of a column projected onto this street. */
    public int along(int x, int z) {
        return alongX() ? (x - ax) * dir.getStepX() : (z - az) * dir.getStepZ();
    }
}
