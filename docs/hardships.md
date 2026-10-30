# Town hardships

Besides [fires](fires.md), towns face sickness, bad harvests and the hunger that follows, and rats in the stores. The code is in `livingrealms.hardship` (`Hardships`, `HardshipConfig`, `HardshipEvents`), the City Manager card in `client/ui/HardshipPanel.java`. Each town's state is saved with the settlement under `hardship`; older saves load healthy.

Every town rolls once a minute (staggered, `Hardships.CHECK` = 1200 ticks), loaded or not, player and NPC alike. Rates below are per settlement day (`simulation.dayLength`).

## Sickness

**Risk.** Daily chance = `hardships.sicknessDailyChance` (default 0.01) × min(2.5, population / 24), times:

| | |
|---|---|
| crowded (90%+ of housing) / overcrowded | ×1.3 / ×1.8 |
| a siege (a raid on a walled town, or a blockade) in the last three days | ×3 |
| poor diet (0–1 kinds of food in store) / varied (4+) | ×1.5 / ×0.8 |
| going hungry | ×1.6 |
| a standing **Chapel** | ×0.75 |
| an **Apothecary** (or a pack building named as an infirmary, herbalist, healer or hospital) | ×0.6 |
| the **Herb Lore** research (Economy, after Agriculture) | ×0.6 |

Towns under 4 people never fall sick. **Caravans**: a caravan arriving from a sick town brings the sickness with chance `caravanContagion` (0.35) × the town's protection above, unless the receiving town is in quarantine.

**Course.** An outbreak starts with 8% of the people ill (at least one). Per day:
- spread: 0.6 × crowding × (fading to a fifth over six days) × the sick × the share still healthy; ×0.8 with a chapel, ×0.6 with an apothecary, ×0.6 with Herb Lore;
- recovery: 0.25 of the sick; ×1.3 chapel, ×1.6 apothecary, ×1.5 Herb Lore, ×1.5 while remedies are in store;
- deaths: `sicknessDeathRate` (0.03) of the sick; ×0.75 chapel, ×0.6 apothecary, ×0.6 Herb Lore, ×0.7 remedies, ×2 when hungry. Each death takes one from the population (never the last), is logged and fires `Hardships.Event.DEATH`.

**Remedies.** Honey bottles, golden apples, golden carrots and glistering melon slices in the stockpile are given out, one for every two sick a day. (Potions cannot be used: the stockpile keeps no NBT.)

**Effects.** Workers produce `1 − 0.6 × sick share` (`Simulation.produce`), builders work at the same rate (`Simulation.speed`), mood −25 × sick share (at least 2, "sickness"). Sick townsfolk in loaded towns shuffle (Slowness) and sneeze (particles).

**Quarantine.** The City Manager's switch (`quarantine` action, owner only) closes all the town's trade routes ("Quarantine in X"): no caravan sets out to or from it, and none can bring the sickness in. NPC towns of Hamlet rank and up quarantine themselves while sick (`npcQuarantine`). Caravans already on the road still arrive.

It is over when fewer than half a person is still sick. Outbreaks that touched 3+ people or killed anyone go into the chronicle: "The sweating sickness of year 2 laid 9 of Mudbury's people low; 2 died. It came with a caravan from Sandmouth. The apothecary's remedies saved many."

## Bad harvests and hunger

A town with farms (NPC towns: plains towns too) has a `harvestDailyChance` (0.02) chance a day of a bad harvest for `harvestDays` (3, ±1) days: **drought** in desert and savanna (farms yield 30%), a **cold snap** in snowy and taiga lands (50%), **blight** elsewhere (40%). The loss is ×0.7 with the **Granaries** research and ×0.8 with a standing **Storehouse**. NPC towns' grain, bread, carrots and potatoes for trade are cut the same way. Mood −3 ("poor harvest").

A town **goes hungry** when its stores run out: for player towns, when the simulation finds nothing left to eat (`starving`); for NPC towns (which keep no larder), when a bad harvest leaves fewer than 2 food per person in store. While hungry:
- no growth, and families leave (the existing starving rules), mood −30 (player towns) or −20 (NPC towns, "hunger"), so loyalty of conquered towns falls with it;
- builders work at 60%;
- sickness is likelier and deadlier;
- the town's trade **wants** 6 bread, 2 potatoes and 2 carrots more per person (3 bread per person during a bad harvest without hunger), so the price rises and neighbours' caravans bring food (`Trade.wants`).

When it has food again, a hunger that lasted a quarter-day or more goes into the chronicle with its cause and, if caravans brought food, where from: "The hungry days of year 3: a drought burned the fields, and Dustwell went short for 1 day, until caravans from Breadly brought food."

## Rats

A town holding 128+ grain, bread, potatoes, carrots and beetroot has a `verminDailyChance` (0.01) chance a day of rats (halved with a storehouse). For two days they eat a tenth of that food a day (halved by a storehouse, halved again by Granaries). 32 or more eaten is chronicled.

## Bounties

A sick town's bounty board asks for honey bottles ("the sick need remedies"), then golden apples; a hungry town (or one in a bad harvest with little in store) asks for bread. These come before the builders' shortages (`Hardships.bounty`, called from `Bounties.deliver`). Delivered goods go into the stockpile, where remedies and food do their work.

## The City Manager

The **Hardships** card on the Overview (between the town switches and the town log) shows the sickness, harvest and rat risk (Very low / Low / Moderate / High, or Off), with tooltips listing what raises and lowers each, and the quarantine switch for the owner. Below it each current trouble: what it is, and what it does ("Workers −24% · mood −10", "Farms yield 30% · caravans bring food", "No caravans to or from the town").

## For other features

```java
Hardships.listen((settlement, event, detail) -> { ... });   // OUTBREAK, DEATH, OUTBREAK_OVER, HARVEST_FAILED, HARVEST_RECOVERED, HUNGER, FED, VERMIN, VERMIN_GONE
Hardships.isSick(settlement, entityUuid);                  // a steady share of townsfolk, by id
Hardships.outbreak(settlement, now, "a caravan from X", random);
```
DEATH's detail is the sickness's name: a named-citizen system can pick who died and remove them.

Hooks into shared code (one line each): `Simulation.produce` (`output`), `Simulation.speed` (`workFactor`), `Simulation.happinessTarget` (`mood`), `Trade.production` (`tradeOutput`), `Trade.wants` (`wants`), `Trade.routes` (`routeBlock`), `Trade.deliver` (`caravanArrived`), `Bounties.deliver` (`bounty`), `Actions` (`quarantine`), `View` (`hardship`).

## Config (`livingrealms-server.toml`, `[hardships]`)

| Key | Default | |
|---|---|---|
| `enabled` | true | Off: no hardships; any under way end at once. |
| `sickness` | true | Outbreaks, contagion and quarantine. |
| `sicknessDailyChance` | 0.01 | Base daily chance for a town of 24. |
| `caravanContagion` | 0.35 | Chance a caravan from a sick town carries it. |
| `sicknessDeathRate` | 0.03 | Share of the sick who die a day before care; 0 = never fatal. |
| `badHarvests` | true | Droughts, blight, cold snaps. |
| `harvestDailyChance` | 0.02 | |
| `harvestDays` | 3 | |
| `vermin` | true | Rats in the stores. |
| `verminDailyChance` | 0.01 | |
| `npcQuarantine` | true | Sick NPC towns close their roads. |

## Self-test

`./gradlew runServer -PhardshipTest -PhardshipTestDir=<fresh dir>` writes `hardship-test-result.txt`: risk factors (size, crowding, siege, diet), a siege raid being remembered, a certain roll starting an outbreak, the same outbreak with and without chapel + apothecary + Herb Lore (spread, recovery, deaths, length, peak, remedies used), deaths reported to listeners, the chronicle entry, happiness and work effects, medicine bounties and trade wants, NPC self-quarantine; quarantine shutting a real route and stopping caravans, then reopening it; contagion by caravan (blocked by quarantine, by a zero chance, and carried through `Trade.deliver`); drought by biome, its cut to farm output and NPC trade, granaries and storehouse softening it; hunger onset, slower builders, mood, the bread need spike and bounty, a bread caravan ending it and the famine chronicled; rats eating grain, less with a storehouse, chronicled; save/load and old saves; and everything switched off.

## Not done

No floods. Sick townsfolk are a share of the visible settlers, not individual people (a citizen system can hook in through the listener). Potions cannot be remedies (stockpile has no NBT). Medicine bounties only appear when the board has room for a delivery.
