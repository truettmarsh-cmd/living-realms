# Battle Reports

After a fight, your recruits' battle is written up as a short after-action report. The last 20 are kept per player and read in **Recruit Command → Reports**.

## What counts as a battle

- A battle starts with the first blow struck or taken by one of your recruits: town soldiers, Royal Guards, or any Villager Recruits recruit you own, in a town or not.
- Blows within 64 blocks of the fight join it; a fight further off is a separate battle.
- It ends after 20 seconds with no combat by those recruits.
- Blows against or from yourself, your own recruits and your tamed animals don't count. Fire, falls and drowning count only during a battle already going on.
- A scuffle with no kills, no fallen and under 10 damage both ways is dropped, not reported.
- A battle still going when the server stops is reported as it stands.
- Recruits owned by NPC kingdoms are not tracked.

## What a report holds

| Field | |
|---|---|
| When | Game day and time of day of the first blow, and how long it lasted (first blow to last). |
| Where | The nearest town's name (inside its land or within 48 blocks of it), otherwise the coordinates. |
| Kind | Skirmish, Dragon attack, Raid defended, Raid attacked, Siege withstood, Siege laid. The highest kind seen during the battle wins, so a fight that turns into a raid is reported as the raid. |
| Enemies slain | By type ("3 Zombie, 2 Skeleton"). |
| Fallen | Recruits who died, by name and recruit UUID. |
| Wounded | Survivors who took damage, worst first, with their health at the end. |
| MVP | The recruit with the highest Merit score for the battle: 10 per kill, 1 per 10 damage dealt. |
| Advances | Skill ranks and promotions, "can now choose a specialist path" (rank 4), path skill and legendary mastery unlocks, new legendary perks and Recruits levels gained during the battle, and saves from a killing blow ("Undying Rage held", "Saved by Bryn the Saint"). |
| Roll | Every recruit who fought: damage dealt, kills, damage taken. |

Raids and sieges are recognised from Living Realms raids: a raid on a town you own, fought within 48 blocks of it, is a raid defended; a raid your recruits were sent on is a raid attacked; behind walls either is a siege. A dragon among the enemies, or a wild dragon attacking the town, makes it a dragon attack.

## When it ends

- A toast: the kind and place, the tally ("5 slain · 1 fallen · 2 wounded") and the MVP.
- For raids, sieges, dragon attacks and any battle where a recruit fell, a gold chat line with the same in full.
- A skirmish or dragon attack near one of your towns in which recruits fell adds a line to that town's chronicle ("In a skirmish against 3 zombie near Ashford, Cade fell."). Raids and sieges are chronicled already. A recruit whose fall has its own entry (a notable soldier, or a bonded partner's grief) is left out of the line, and the line is skipped if nobody is left.

## The Reports page

Recruit Command (shift + right-click a recruit) has a **Reports** page. On the left, your battles, newest first; a blue dot marks those the open recruit fought in, and **This recruit** filters to them. On the right, the chosen report. On narrow windows the list opens each report full width with a Back button. The page refreshes every 3 seconds while open and says when a battle is still being fought.

## Storage

Reports are saved with the overworld (`data/livingrealms_battles.dat`), keyed by player UUID, and each recruit by its UUID. They are not stored in any town and don't need one: town names, raids and the chronicle are only read through `livingrealms.battle.BattleContext`, and a report without a town is complete with coordinates instead. Battles in progress are kept in memory only.

## Testing

`./gradlew runServer -PreportsTest -PreportsTestDir=<fresh dir>` (copy `eula.txt` and `server.properties` from `run-military-test/`) writes `reports-test-result.txt`. It fights real recruits and zombies through the real damage and death events, and checks the kinds, kills, fallen, wounded, MVP, advances (Undying Rage and the Saint included, with the damage that really landed), toast and chat text, chronicle line and what it leaves out, saves, the 20-report cap and a report without a town.
