# Sieges

A raid on a town with walls is a siege: the walls, gates and towers the villagers built now decide how the fight goes. Code: `settlement/Siege.java` (state and rules), hooked into `Raids.start`, `Raids.tickRaid` and `Raids.finish`. A town without a standing gatehouse or wall section is raided exactly as before.

## Phases

Each raid keeps a `Siege` (saved with the raid under `siege`). The phase shows at the start of the raid status line, and the City Manager's raid card shows the breach bar instead of the centre bar until the walls are breached.

| Phase | What happens | Ends when |
|---|---|---|
| **Approach** | The army marches on the weak point and forms up 14 blocks outside it. | Half the attackers are within 16 blocks of the breach. |
| **Breach** | A ram crew (gatehouse) or sappers (wall section) work at the weak point. | The breach is made, or an attacker gets inside the wall some other way. |
| **Assault** | The attackers go in through the breach and fight for the town, as in an open raid. | The attackers hold the centre. |
| **Hold** | No defenders left and an attacker within 12 blocks of the centre: 60 seconds take the town. | The town falls, or a defender returns and the phase drops back to Assault. |

An unwalled town starts in Assault. A raid saved before sieges existed loads with no siege and carries on as an assault. A walled town's raid lasts up to 15 minutes before the attackers give up, instead of 10.

## Gates shut

When the raid starts, every standing gatehouse of the outermost walled line lowers a portcullis: iron bars across the middle row of its passage, 5 wide and 4 high. What stood in each block is remembered, and gates in ground that was unloaded are shut once it loads. When the raid ends, however it ends, the bars still standing are put back the way they were. A gate that was unloaded at the end is queued for repair, and the repair clears its passage. Defenders inside stay inside. The melee hold the inside of the breach, and the bowmen go up on the walls.

## The weak point

`Siege.choose` looks at every piece of the line. The score is half its distance from where the army arrives plus the seconds it would take to break, and the lowest score wins:

- an **unfinished piece** (not yet built, or still being built) is a gap: no time at all;
- a **gatehouse**: 40 s × strength;
- a **wall section**: 70 s × strength;
- towers are not attacked, and water or cliff stretches with nothing built are skipped.

**Strength** multiplies: the masonry's hardness (stone bricks 1×, cobblestone 1.33×, sandstone 0.6×), +15% per rank above Village, ×1.4 for a kingdom's capital, +10% per standing tower or gatehouse within 24 blocks, and ×1.25 for the city wall. A plain village gate falls to three men in about 20 s, and a capital's in about a minute.

## Breaching

The three melee attackers nearest the breach are the crew. They stand at the foot of the wall, 4 blocks outside, and do not chase defenders. Each second, progress grows by 20 ticks × (0.5 + 0.5 per crewman within 6 blocks, up to 3), plus 20 per siege engine with the army. Crumbling cracks show on the blocks, and a ram or pick sound plays. When progress reaches the time needed, real blocks come down:

- **gate**: the portcullis and the bottom course of the arch over the middle of the passage;
- **wall**: every block above the bottom course in the wall columns within about 2 blocks of the breach point (3 to 5 wide, the full thickness). The bottom course is left as a floor.

Both sides hear it, and the piece is noted for repair.

## Defenders on the walls

While the attackers are outside (before the breach, or while most of them are still outside), the defenders' Battle AI orders change:

- **Bowmen and crossbowmen** take posts on the tops of towers and gatehouses and on the wall walk, nearest the breach first, within 36 blocks of it (at most 12 posts). Each shoots the nearest attacker in reach. Recruits' pathfinding does not climb ladders, so a bowman who reaches the foot of its post (within 6 blocks) is put on top, as if it climbed the tower ladder.
- **Melee** hold a ring 5 blocks inside the breach. They fight only attackers inside the walls or at the breach.
- Royal Guards keep escorting their ruler, as before.

The attackers' melee fight only defenders outside the walls before the breach. Their archers shoot at the defenders on the walls. After the breach, attackers still outside are routed to the inside of the breach.

## Towers shoot (Town rank and up)

Every 3 seconds, up to four towers or gatehouses within 40 blocks of the breach each loose two arrows (3 damage, not pickable) from their roofs at the nearest attacker outside the wall.

With the **Scorpions** research, wall towers, gatehouses and watchtowers also loose heavy bolts at raiders in the town's land, and at dragons before anything else (see [dragons](dragons.md)).

## Blockade and surrender

While at least 3 attackers, two thirds of them, are camped outside the wall (within 48 blocks of it) before the breach, the town is blockaded. Every 20 seconds the blockade eats a sixth of a day's food (4 per townsperson and 5 per soldier, in nutrition) from the stockpile, the same way the simulation eats. NPC towns keep no food stock, so they get notional provisions of a day's food × (rank + 1).

Once the stores are empty the town is starving. The defenders lose regeneration, are weakened and lose morale. After 60 seconds of starving the town **surrenders**, unless its ruler is in it. A surrender ends like a win (the town transfers, and the war score counts it as taken), but nothing is broken: the gates simply open.

## Siege engines (pack mods)

With Siege Weapons or Medieval Siege Machines (`SiegeTrains`), the crews aim at the breach point (the wall top) until it is open, then at the centre with the old rules (`BattleAI.firePoint`). Each engine also speeds the breach.

## Repairs

When the raid ends, every broken gate or wall section is set to Repairing. This is what the Repair button does: a project of its own, paid from the stockpile (NPC towns: treasury), that the builders see to before choosing new work. The repair puts the blueprint's blocks back and clears the gate passage. The chronicle notes it.

## NPC kingdoms

NPC raids go through `Raids.start`, so they get the same sieges. When choosing a target, `Strategy.raidScore` counts a walled town's garrison as 1.3 times stronger.

## Tests

`./gradlew runServer -PsiegeTest -PsiegeTestDir=<fresh dir>` (`SiegeSelfTest`, result in `siege-test-result.txt`):

- It builds a Village wall (`CityPlanningSelfTest.buildWalls`) round a player town.
- It checks the weak point: the nearest gate, or an unfinished stretch, and that a capital's walls are stronger.
- It saps a wall section (real blocks down, floor left), queues the repair and rebuilds it.
- The NPC scheduler starts a raid on the town. The test checks that all four gates fill with iron bars, then Approach → Breach in the status and the City Manager view, a bowman posted on the wall and climbing it, the footman inside the gate, and save/reload (plus an old raid tag).
- The ram breaks the east gate (portcullis and arch down, the other gates still shut). The test checks tower volleys and the blockade eating food.
- Then Assault → Hold → capture. The test checks that the gates reopen and that the gate is queued for repair and rebuilt with its passage open.
- A second NPC kingdom blockades the now-NPC town, which starves and surrenders with its walls whole.
- An unwalled town still goes straight to Assault and is taken by holding its centre.

`WarfareSelfTest` (unwalled towns) still passes unchanged.

## Limits

- There is no ram entity. The crew are ordinary soldiers standing at the gate.
- Ladders up the outside of the wall are not used. Wall sections are sapped.
- Only the outermost walled line is besieged. An inner (old-town) wall keeps its gates open.
- The climb onto the walls is a short teleport at the foot of the post.
- Engines aim at the breach while the ram crew works there, so mod engines can hit their own crew.
