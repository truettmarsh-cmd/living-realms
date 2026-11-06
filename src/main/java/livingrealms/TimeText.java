package livingrealms;

/**
 * How long something lasts, the same way on every page: a day or more in game days ("3 days", "1d 6h", in game hours),
 * anything shorter in real minutes ("14 min"). {@link #real} gives the real-time length for tooltips.
 */
public final class TimeText {
    /** "3 days", "1 day", "1d 6h", "14 min", "under a minute". */
    public static String span(long ticks, long dayTicks) {
        dayTicks = Math.max(1, dayTicks);
        ticks = Math.max(0, ticks);
        if (ticks >= dayTicks) {
            long days = ticks / dayTicks, hours = ticks % dayTicks * 24 / dayTicks;
            if (days < 3 && hours > 0) return days + "d " + hours + "h";
            long rounded = (ticks + dayTicks / 2) / dayTicks;
            return rounded + (rounded == 1 ? " day" : " days");
        }
        if (ticks < 1200) return "under a minute";
        return (ticks + 600) / 1200 + " min";
    }

    /** Real time, for tooltips: "about 2 h 20 min", "about 14 min". */
    public static String real(long ticks) {
        long min = Math.max(1, (ticks + 600) / 1200);
        if (min < 60) return "about " + min + " min";
        long h = min / 60, m = min % 60;
        return "about " + h + " h" + (m == 0 ? "" : " " + m + " min");
    }

    private TimeText() {}
}
