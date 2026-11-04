# Brothers-in-arms

Recruits who fight side by side and live grow attached to each other.

## Who can bond

Any two recruits owned by the same player: town soldiers trained in a barracks, recruits hired the Villager Recruits way, or a mix. Workers don't bond. Bonds are saved per recruit UUID in `data/livingrealms_bonds.dat` (`BondData`, its own saved data), so nothing depends on a town, its stockpile or the settlement save.

## Earning points

- A recruit is **fighting** when it hits, or is hit by, something that isn't its owner or one of its owner's recruits (`CombatClock`, the same rule resupply uses).
- Two recruits who are both fighting within 12 blocks of each other (each within 10 seconds of the other) share a fight. Each shared fight adds a point to their battle, at most one every 5 seconds and **3 a battle**.
- The battle ends when the pair hasn't fought together for 30 seconds. If both are still alive, the points are added. A recruit who dies first earns its partner nothing from that battle.
- A pair gains at most **6 points a day** (a Minecraft day of game time).
- A tie under 10 points that nobody fights in for 10 days is forgotten. Real bonds are forgotten only when the partner is gone: discarded, now another player's, or unseen for 5 days while the other one was about (two recruits both left in unloaded chunks keep their bond).

## Levels

| Level | Points | While the two fight within 10 blocks |
|---|---|---|
| Comrades | 10 | +3% damage |
| Sworn Kin | 30 | +6% damage; avenge |
| Blood Oath | 60 | +10% damage, 10% less damage taken; avenge |

- Only the strongest bond within 10 blocks counts; bonds don't add up.
- **Avenge**: when a Sworn Kin or Blood Oath partner within 10 blocks takes a hit of at least a quarter of its max health (as it lands, after a Saint or Undying Rage), the recruit deals +10% damage for 5 seconds (once every 30 seconds).
- A recruit has at most **3 bonds** and at most **1 Blood Oath**. A tie that would make a fourth bond stays at 9 points, and a second Blood Oath stays at 59, until a place is free. A partner that is gone holds no place: one discarded (not just unloaded), one that now belongs to another player, or one not seen loaded for 5 in-game days. Every loaded recruit with bonds is seen about every 30 seconds.
- Bond damage bonuses (the bond, vengeance and avenge) share their own +15% budget, on top of the skill, weapon, legendary and path budgets; the reduction is capped at 10%. Grief comes off after the cap.

## Loss

When a bonded recruit dies, each partner:

1. Loses morale at once: 5 for a comrade, 10 for sworn kin, 15 for a blood oath. This goes through Villager Recruits' morale and, with the Recruits Morale addon, its battle morale too.
2. **Grieves** for 3 minutes: −10% damage.
3. Then has **Vengeance** for 10 minutes: +8% damage against the kind of creature (or player) that killed its partner. A death with no killer, or at the owner's hand, brings only grief.

A deeper loss replaces a lighter grief still running. The dead recruit's bonds are gone.

## Messages and the chronicle

The owner, if online, gets a chat message when a pair becomes Comrades, Sworn Kin or a Blood Oath, and one line when a bonded recruit falls, naming every partner who grieves ("✝ Aldric and Dunn grieve for Bera, and will seek vengeance on the Zombie."). If either recruit is a town soldier, Sworn Kin, Blood Oaths and the fall of a sworn or blood-oath partner are written in that town's chronicle, one entry per fall; a battle report's skirmish line then leaves that recruit out.

## Recruit Command

The Overview page has a **Brothers-in-arms** card: each bond's partner, level and points toward the next level, a green dot when the partner is within 10 blocks, the closest forming tie if a place is free (in orange when a cap holds it back), and any grief or vengeance with the time left. Hover a row for its effect.

## Promotions

A Villager Recruits promotion through Recruit Command replaces the recruit with a new entity; its bonds, grief and supply settings move to the new one. A promotion done through Villager Recruits' own screen does not: the new recruit starts without bonds, and the old one is known to be gone, so its partners' bonds with it hold no place and fade at the next sweep (at the latest after 5 days unseen).

## Test

`./gradlew runServer -PbondsTest -PbondsTestDir=<fresh dir>` (copy `eula.txt` and `server.properties` from `run-military-test/`). It spawns real recruits hired straight to a player and checks forming in battle, the battle and daily limits, range and owner checks, levels and caps, gone partners freeing their place and being pruned, buffs through the real damage hooks and their budget, avenging within the budget, grief, morale and vengeance through a real death, the single grief line and chronicle entry, promotion, the save round trip, the Recruit Command view, the chronicle for a town soldier and clearing fights in progress when the server stops. It writes `bonds-test-result.txt`.
