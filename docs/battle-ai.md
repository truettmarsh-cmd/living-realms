# Battle AI

How Living Realms soldiers fight once a raid reaches a city. Kingdom-level decisions (who declares war, which city to raid, peace) are elsewhere; this note covers only the fight itself.

## Where it runs

`Raids.tickRaid` runs once a second for each active raid. It finds both armies: the expedition, and the loaded defenders standing in the target city, plus the ruler if present. Then it:

1. sets each side's owner and Recruits team, as before, so that Recruits and Village Expansion see two enemy factions;
2. registers both sides with `BattleAI.enlist`;
3. asks `BattleAI.plan` for one `Order` per soldier: a target, a place to hold, and a stance;
4. carries out each order through `RecruitsBridge` (`raidOrder` or `fallBack`). It calls `Realms.assign` first, so Village Expansion's `RecruitTargetStickiness` records the target as a group assignment and keeps it.

`BattleAI.plan` reads entities but changes nothing, so the self-tests can check each rule directly.

## How Recruits' own AI is used

Recruits gives every soldier a hold-position goal (priority 3, controls movement) and a melee goal (priority 2, controls movement and looking). Bowmen and crossbowmen also get a ranged goal (priority 4), but it only controls looking. That has two consequences:

- A melee soldier with a target chases it whatever its hold position is.
- An archer walks to its hold position and shoots from there. Before this change the raid order held archers at their target's feet, so they walked into melee. They now hold a standoff point.

The orders use only Recruits' public API: aggro state, follow state 3 with a hold position, `setTarget`, and strategic fire for siege crews. Recruits' formations (`FormationUtils`) are built for orders a player gives with the command screen, and are not used here.

## Rules

**Target choice.** Each soldier scores every enemy within 48 blocks. The score starts at the distance in blocks, then:

| Situation | Change |
| --- | --- |
| Shieldbearer, enemy within 8 blocks of one of our archers | −14 |
| Other melee soldier, same situation | −4 |
| Archer aiming at an enemy archer | −6 |
| Wounded enemy | up to −6 |
| Defender, enemy within 8 blocks of the ruler | −14 |
| The soldier's current target | −3 |
| Each melee soldier beyond the second already on that enemy | +6 |

The lowest score wins. Shieldbearers choose first and archers last. The result: shields screen the archers, the line spreads over the enemy line instead of piling onto one man, the wounded are finished, and targets don't flicker from second to second.

**Archers keep their distance.** An archer between 10 and 22 blocks from its target holds where it stands. Closer or farther, it holds a point 14 blocks from the target, on its own side.

**Falling back.** A soldier below 30% health leaves the line. With broken Recruits morale (below 20) the threshold is 45%. It turns passive, drops its target and walks to the rally point:

- for attackers, the city edge on the side they came from;
- for defenders, 8 blocks behind the centre, away from the attackers.

At the rally point it gets Regeneration I, and it rejoins at 60% health. At most half a side falls back at once, the worst hurt first. The last soldier of a side never falls back, and nobody falls back when no enemy is in reach. The flag is saved on the entity (`livingrealms_fallback`). A soldier that was unloaded when its battle ended gets it cleared the next time it loads.

**Defenders close ranks.** When the attackers outnumber the local defenders 1.5 to 1 (with at least two attackers), the defenders form a ring 3 blocks around the centre. Melee defenders fight only enemies within 10 blocks of themselves or of the centre; archers shoot anything within 28 blocks. A held centre blocks capture, so a small garrison stays in the fight instead of dying piecemeal in the streets. The city log records the moment it starts.

**Royal Guards** are left out of raid orders and keep escorting their ruler. Their escort now treats enemy raid soldiers near the ruler as threats. Before, it only looked at the attacker who last hurt the ruler, or at monsters. Ordinary defenders also go first for any enemy within 8 blocks of the ruler.

**Siege crews** (compat, `SiegeTrains.aim`) shell the city centre while no attacker is within 10 blocks of it. Once the attackers reach it, the crews shift their fire to the densest group of defenders that is at least 10 blocks from every attacker. If there is no such group, they cease fire. Each change is logged once.

**Specials and perks.** Recruits are not `Enemy` mobs, so before this change several abilities ignored enemy soldiers: Taunt, Cleave, Dragonfire Strafe and the hard-fight check of Rally Cry. So did the on-kill perks (Victor's Breath, Battle Rhythm), kill XP and damage XP. `SoldierSpecials.hostile` now counts monsters plus the other side of any battle the soldier is enlisted in. Monsters only count as before.

## Readability

The City Manager's raid status line now reports soldiers falling back on either side, and defenders holding the centre. For example: `Raid in progress: 6 attackers (1 falling back); 3 local defenders, holding the centre`. Regrouping and siege fire changes go to the city log. Specials keep their existing particles and sounds.

## Unchanged

- Unloaded soldiers still pause a raid; they are never counted as dead.
- A soldier falling back stays in the raid's roster.
- Capture still means no local defenders and an attacker within 12 blocks of the centre for 60 seconds. Attackers stop falling back once no defender is in reach, so they advance to take the centre.
- The `BattleAI.enlist` registry lives only in memory. It is rebuilt every second while a raid ticks and released when the raid ends.

## Tests

`WarfareSelfTest` covers the following:

- in a real raid: mutual hostility; the regroup announcement and status line; a wounded attacker falling back, passive and still enlisted; its return at 60% health; release of the registry after the battle;
- on real recruits: archer standoff and hold; the shield screen; footmen taking the nearest enemy; the melee spread; finishing the wounded; protecting the ruler; falling back, the last stand, no fall-back without enemies, and the half-side cap; regrouping and still fighting at the centre; the three siege fire rules; a Royal Guard engaging a raid soldier near its ruler.

`CompatSelfTest` (`-PpackMods`) checks that a real trebuchet crew shifts its fire, holds it, and shells the centre again.

## Limits

- Orders are refreshed once a second. Between refreshes, Recruits' own goals drive the soldiers.
- Positions are chosen in the horizontal plane. Recruits' `getPositionOrSurface` picks the ground level, which can put a hold point on a roof in a dense town.
- Mounted soldiers and dragon riders never join raid expeditions, so cavalry tactics apply only to defenders, through Charge.
- Village Expansion's own field armies and sieges (between VE villages, or against cities you took from VE) run on VE's AI, not this one.
