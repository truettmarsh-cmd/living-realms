# Dragons in kingdom play

Dragons from **Ice and Fire** become part of town life and war: a town can keep one in a Dragon Roost, wild dragons sometimes attack towns, towers get scorpions to shoot them down, and NPC kingdoms bring their dragons on raids. Code: `livingrealms/dragon/` (`Roosts`, `Flight`, `DragonAttacks`, `Sorties`, `Scorpions`, `DragonApi`), the blueprint in `building/RoostBlueprint.java`.

Ice and Fire and Dragon Bond are optional. Nothing here loads a class of either mod: `DragonApi` works by registry names and reflection, and checks `DragonRiders.installed()` first. Without Ice and Fire the roost can still be built, but it stays empty ("Needs Ice and Fire"), no wild attack or sortie ever starts, and only the scorpions work (against raiders and hostile mobs).

## Dragon Roost

- **Research Dragonry** (Military, after Cavalry and Urban Planning; 600 points and 16 gold ingots). The roost is a City building (rank 5+), one per town, in the Stables category (the outer half of town; its keepers count as stablehands, and it counts as Stables).
- **The building**: a 21 × 21 stone court open to the sky. In the middle is a raised nest of hay with a gold block at each corner; four lantern pillars with banners stand at the corners, and a slab-roofed lean-to along the back holds the keepers' barrels, hay and cauldron. The front (south) is open. It is nearly all stone, so its fire risk is low. With **Dragon Bond** installed, the nest block is Dragon Bond's Dragon Nest, and a roost dragon gets the roost as its Dragon Bond home: it heals and bonds faster there, and the court scores well for roof, walls, bedding, hoard, light and banners.
- **Getting a dragon** (with Ice and Fire), whichever comes first:
  - **Hatch an egg.** Put an Ice and Fire dragon egg into the stockpile, for example through a delivery chest. The keepers take it, it lies in the embers for `dragons.hatchTicks` (default one day), and then a tamed dragon of that colour hatches on the nest. It belongs to the town: its owner is the ruler, and if the town changes hands, the dragon answers to the new ruler. The chronicle records the hatching.
  - **Adopt your own dragon.** Order one of your tamed dragons to **sit** within 16 blocks of the nest. It moves in and stays yours. If the town changes hands, it leaves with you.
- **Living there.** A roost dragon perches on the nest (sitting). If it strays more than 6 blocks it flies back, and if it is more than 64 blocks away while the roost is loaded, it is brought straight home. It is left alone while it carries a rider, escorts you, or is fighting. Each day it spends at the roost, the keepers' food adds `dragons.roostGrowthDays` (default 4) days of growth until stage 4, so a hatchling can carry a rider in about two weeks instead of fifty days.
- **Dragon Riders.** Training a Dragon Rider still needs the Dragon Riders research, a Barracks and Stables (a roost counts as Stables). A roost dragon of stage 3 or more is the mount wherever it perches in town; it does not have to be within 48 blocks of the barracks. Your other free dragons near the barracks still work as before.
- **If it dies,** the chronicle records it and the roost is empty again.

NPC towns build roosts like any other building once they have the research. An NPC roost without a dragon gets a new egg after `dragons.npcEggDays` (default 3) days ("bought from dragon hunters"), and the dragon is tamed to a keeper's name that stands for the NPC kingdom.

## Wild dragon attacks

Every minute, each loaded town has a `dragons.wildAttackChance` (default 0.02) chance that the nearest wild Ice and Fire dragon within `dragons.wildAttackRange` (default 160 blocks) is drawn to it. A wild dragon is untamed, stage 2 or more, awake and alive. A wild dragon that flies into a town's land on its own attacks it too. Each town gets one attack at a time and is then left alone for a day. Switch it all off with `dragons.wildAttacks = false`.

During an attack:

1. **The alarm.** The bell rings, the town log and the ruler hear "Dragon! A wild fire dragon … is attacking", soldiers take their alarm posts, and the City Manager shows "Dragon!" with the attack's progress on the overview.
2. **The dragon is flown** (`Flight`). Its own AI is switched off for the attack. It circles 16 blocks above the town centre, and every 3 seconds it breathes on the nearest of the town's people: townsfolk, soldiers, or the ruler outside creative mode. Each breath hits at most 3 people for 5 damage (7 for lightning); fire sets them alight and ice slows them. Every other breath goes at a building instead, until the fire allowance `dragons.maxFiresPerAttack` (default 2 buildings) is used up. Fire and lightning dragons set the building alight through `Fires.ignite`; ice dragons do not start fires. Their own griefing breath is not used.
3. **The town fights back.** Every second, the town's bowmen, crossbowmen and dragon riders within 72 blocks target the dragon. The town's own roost dragon (stage 2+) rises against it. The scorpions shoot (below).
4. **The end.** After `dragons.attackSeconds` (default 60), or once the dragon is below 30% health, it flies 96 blocks away and gets its own AI back. If it is killed, the attack ends at once. The chronicle records it as "A wild fire dragon fell upon Testford and set the bakery alight, then flew off." or "… The scorpions brought it down."

**Fire.** A building set alight by the dragon is an ordinary town fire with the dragon as its cause, for example "a wild fire dragon's breath". The bucket brigade comes, the fire is kept inside the town, damaged buildings are queued for repair, and the fire's chronicle entry names the dragon ("A fire started by a wild fire dragon's breath damaged the cottage…"). Fire that Ice and Fire dragons start by themselves is still found by the fire watch and blamed on "dragonfire" (see [fires](fires.md)).

A dragon's AI is switched off only while it is flown. A tag on the dragon (`livingrealms:flown`) gives it back on the next load if the server stops mid-flight.

## Scorpions

- **Research Scorpions** (Military, after Crossbows and Fortifications; 300 points, 24 iron ingots, 16 string). From Town rank, every standing **wall tower, gatehouse and watchtower** has a scorpion. This is a town-wide upgrade, with no extra block on the tower.
- **When they shoot.** Every 3 seconds, while there is a threat: a dragon over the town (an attack or a sortie) or any wild dragon in its land. Without dragons, the threats are raiders inside the town's land during a raid, or hostile mobs in town. Dragons are always shot first.
- **The volley.** Up to 6 towers, nearest the threat first, each loose one heavy bolt at the nearest threat within 80 blocks. A bolt is a server-side arrow with no gravity that flies at speed 3.5 from above the tower's edge, led to where the target is going, and is gone after 3 seconds. It does `dragons.boltDamage` (10) to anything and `dragons.boltDragonDamage` (30, after armour) to a dragon. A bolt never hurts the town's own people.
- These volleys are separate from the siege tower arrow volleys in `Siege.volley`, which go on as before. No siege-mod ballista entity is used.

## NPC kingdoms: dragon sorties

When an NPC kingdom starts a raid (`Raids.start`), it sends a dragon if one of its cities has a roost dragon ready: stage 3 or more, at least half health, not carrying anyone, in the same dimension. One dragon goes per raid, and `dragons.raidSorties` switches this off.

- The dragon appears 40 blocks outside the target on the attackers' side with a Dragon Rider (a Recruits bowman) on its back. Both towns are told ("A dragon! Vulcan of Drakmoor flies against Lowmere with a rider on its back!").
- It circles over the siege breach while the walls hold, and over the town centre otherwise. It breathes on the defenders exactly like a wild attack, with the same fire allowance and limits.
- The defenders' bowmen turn on it, their roost dragon rises, and their scorpions target it first.
- The sortie lasts `dragons.attackSeconds`, or until the dragon is below 30% health, or until the raid ends. Then the rider is gone, and the dragon is put back on its perch with its AI restored.
- Chronicles: the target notes "Vulcan, the dragon of Drakmoor, flew over Lowmere during the raid and set the cottage alight." If the dragon is shot down, both towns record it.

Player kingdoms are not flown: your dragons and Dragon Riders follow your orders and your stances as before.

## City Manager

The overview has a **Roost** row whenever the town has a roost. It shows the dragon's name, element and stage, a health bar and its state (Perched, Flying home, Carrying …, Fighting, On a sortie, Away from the roost), or the egg and how long until it hatches, or how to get a dragon. The tooltip adds the Dragon Bond level, whether it can carry a rider, and whether scorpions are mounted. During an attack the row turns into a red **Dragon!** line with the breaths and fires so far. The chronicle has a new **Dragon** kind (orange) for the roost's completion, hatchings, adoptions, deaths, attacks and sorties.

## Config (`livingrealms-server.toml`, `[dragons]`)

| Key | Default | |
|---|---|---|
| `wildAttacks` | true | Wild dragons attack towns. |
| `wildAttackChance` | 0.02 | Per town and minute. |
| `wildAttackRange` | 160 | Blocks from the centre. |
| `attackSeconds` | 60 | Length of an attack or sortie. |
| `maxFiresPerAttack` | 2 | Buildings one attack or sortie can set alight. |
| `npcDragons` | true | NPC roosts get eggs. |
| `raidSorties` | true | NPC raids bring a dragon. |
| `hatchTicks` | 24000 | Egg incubation. |
| `npcEggDays` | 3 | Days before an empty NPC roost gets an egg. |
| `roostGrowthDays` | 4 | Extra growth per day at the roost (to stage 4). |
| `scorpions` | true | Scorpions shoot. |
| `boltDamage` / `boltDragonDamage` | 10 / 30 | Bolt damage. |

## Saved data

Roosts are saved in `livingrealms_dragons` (world data), keyed by town, not in the settlement. Each roost saves its dragon's UUID, whether the dragon was hatched there, the last-seen name, element, stage and health, any egg and when it hatches, the feeding day and the NPC egg timer. Missing fields load as an empty roost. Attacks and sorties are not saved, because they last a minute; a flown dragon gets its AI back when it next loads.

## Tests

`./gradlew runServer -PdragonTest -PdragonTestDir=<fresh dir>` runs without Ice and Fire, and adding `-Pintegrations` runs with it. This is `DragonSelfTest`, and the result goes to `dragon-test-result.txt`.

- **Both runs:**
  - The roost type is gated (a Town cannot build one; a City needs Dragonry).
  - It is planned and built by the builders. The nest and hoard stand in the open court, and it is less flammable than a cottage.
  - Its completion is chronicled, and the City Manager view has the roost and the scorpions.
  - A zombie in town draws a scorpion bolt that really flies and hits for 10. A bolt cannot hurt the town's own soldier.
  - Save and reload work.
- **Without Ice and Fire:** the roost reads "Needs Ice and Fire". No attack, egg, hatchling, rider mount or sortie happens, and ticks pass quietly.
- **With Ice and Fire:**
  - A red egg from the stockpile hatches a tamed fire dragon on the nest, and the hatching is chronicled.
  - The keepers' food grows it, it perches, and when it strays it is brought home.
  - A Dragon Rider trained across town (more than 48 blocks away) mounts it.
  - A second town's ruler's dragon moves in when told to sit, but not before.
  - A bolt hurts a dragon for 30, and a real bolt from the tower hits one.
  - Then, live on the server tick, a sure roll draws a wild fire dragon to the town:
    - The bell rings, and only one attack runs at a time.
    - The bowmen and the roost dragon turn on it, and the scorpions shoot.
    - It breathes on the town and sets no more than 2 fires. It flies off with its AI back, and the attack is chronicled.
    - The fire names the dragon as its cause, the brigade fights it, and the damaged building is queued for repair and chronicled.
  - At the same time, an NPC kingdom's roost hatches its own dragon and raids another town with it:
    - A rider is on its back, and it flies in over the target.
    - It breathes there with fires capped.
    - The target's scorpions shoot at it.
    - The rider is gone afterwards, the dragon is back on its perch, and the target's chronicle tells of it.

## Limits

- **Tower look.** Scorpions are an upgrade for all towers through research. No scorpion model is placed on the towers.
- **Flight path.** A flown dragon moves only through loaded chunks, and an NPC dragon is placed near the target instead of flying the whole way there.
- **Flight look.** It circles with its AI off, so it shows Ice and Fire's flying pose but not every animation, such as the breath animation, which is set but may not show while flown.
- **Rider.** The NPC dragon's rider rides along and does not fight from the saddle.
- **Defenders' roost dragon.** When it rises against an attacker, it uses Ice and Fire's own AI, so how hard it fights is up to Ice and Fire.
- **Adopting.** Moving a dragon in needs an Ice and Fire sit order. There is no button to release a dragon: order it to follow you (escort) to take it out with you, or give it away.
