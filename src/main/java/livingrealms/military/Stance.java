package livingrealms.military;

/** Standing orders for the whole garrison. */
public enum Stance {
    PATROL("Patrol", "Walk the streets and walls, engage any threat."),
    GARRISON("Garrison", "Stand guard at the barracks, towers and gates."),
    MUSTER("Muster", "Form up in ranks at the town hall."),
    FOLLOW("Follow me", "March with you wherever you go."),
    FREE("At ease", "No standing orders. Command them yourself with Recruits.");

    public final String label, description;

    Stance(String label, String description) {
        this.label = label;
        this.description = description;
    }
}
