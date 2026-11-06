# Wonders

Five great works for a kingdom at the top of the tree. Each is an ordinary building project raised by the capital's builders, block by block from the stockpile, and each one helps every city of the kingdom. Code: `livingrealms/wonders/` (`Wonder`, `Wonders`, `Masterwork`), the blueprints in `building/WonderBlueprints.java`, the test in `settlement/WondersSelfTest.java`.

## Getting one

- **Research.** First **Wonders of the Realm** (new Wonders branch; after Royal Charter and Education; 2400 points, 4 gold blocks and 16 books). Then each wonder has its own research (1800 points each, plus materials):

  | Research | Items |
  |---|---|
  | Grand Cathedral | 24 gold ingots, 16 candles |
  | Royal Academy | 32 books, 4 diamonds |
  | Grand Forge | 8 iron blocks, 64 coal |
  | Grand Exchange | 64 emeralds, 16 gold ingots |
  | Citadel of Heroes | 64 iron ingots, 16 gold ingots |

  Research belongs to a town, so the capital has to know it.
- **Where.** Only in the kingdom's capital, at Capital rank. One of each per kingdom: once a wonder is planned anywhere in the kingdom (building or standing), no other city of that kingdom can plan another. A wonder being torn down frees the place again.
- **Planning.** Wonders are in the building catalogue under their category (Faith, Scholarship, Smithing, Trade, Barracks), and the **Wonders** card on the Kingdom page has a **Plan it** button for each one that is available. Place it in the world or on the City Map like any building. Villagers never choose a wonder on their own (automatic building skips them), and NPC kingdoms do not build them.
- **Building.** Builders place every block and pay for it from the stockpile, so a wonder takes as long as it takes to gather thousands of blocks, and some of them are dear: stained glass and gold for the cathedral, bookshelves and an enchanting table for the academy, anvils and iron blocks for the forge, gold and emerald blocks and glowstone for the exchange, iron blocks for the citadel. The catalogue and the Build tab show the full bill.

| Wonder | Category | Size (w × d × h) | Blocks |
|---|---|---|---|
| Grand Cathedral | Faith | 26 × 45 × 35 | about 5,000 |
| Royal Academy | Scholarship | 31 × 29 × 24 | about 3,900 |
| Grand Forge | Smithing | 30 × 27 × 25 | about 3,000 |
| Grand Exchange | Trade | 31 × 31 × 31 | about 2,600 |
| Citadel of Heroes | Barracks | 31 × 32 × 23 | about 4,400 |

All five follow the town's palette (stone, wood, colours) with fixed accents. They are drawn in code, block by block; nothing is copied from another build or mod.

- **Grand Cathedral**: a cruciform church with a tall buttressed nave and lancet windows of stained glass, a transept, an apse with a gold altar, a rose window over a double door, and two bell towers with gold-tipped spires.
- **Royal Academy**: a two-storey stone college. Lecture benches and an enchanting table ringed with bookshelves stand under a stepped dome with a glass top. The upper floor holds library stacks. There is an observatory tower at the back and a portico of quartz columns at the front.
- **Grand Forge**: a long stone hall with three brick chimneys over campfire hearths, blast furnaces, six anvils, smithing tables and grindstones, iron-capped piers and a wide arched front onto a yard.
- **Grand Exchange**: a quartz colonnade around a court of market stalls. In the middle is a counting house with a treasury of gold and emerald blocks, under a stepped dome topped by a lighthouse lamp.
- **Citadel of Heroes**: a walled ward with corner towers and a gatehouse. It holds a three-storey keep, the Hall of Heroes with champions' plinths (iron, one gilded), a training yard of targets and hay, and the veterans' quarters.

The building also counts as an ordinary building of its category: the cathedral's priests, the academy's scholars, the forge's smiths, the exchange's merchants and the citadel's barracks capacity and training speed.

## What they do

Each wonder works for every city of the kingdom that holds it, as long as it stands (built, being upgraded or under repair).

- **Grand Cathedral**
  - +10 mood in every city ("+10 Grand Cathedral" among the mood reasons).
  - Outbreaks of sickness are 40% as likely. The City Manager lists "Grand Cathedral" among the sickness factors. Once an outbreak starts, it spreads at 70% of the usual rate and the sick recover 1.5× as fast, so it passes sooner.
  - **Spared.** Once a day, when a recruit of the kingdom takes a killing blow inside one of the kingdom's cities, there is a 50% chance it is spared. It is left on 1 HP and carried to the cathedral, with a few seconds of Resistance V and Regeneration II. The town log, the ruler's chat and the battle report note it. The day's blessing is spent only on a recruit that is spared. Damage that bypasses invulnerability (the void, /kill) is never spared. It works at the same priority as Undying Rage and the Saint, so battle reports and bonds see the damage that really lands.
- **Royal Academy**
  - Research ×1.5 in every city.
  - Saved study points (gathered while a town has no project) hold up to 4,000 instead of 2,000. They flow into the next project at up to 4× the town's own rate instead of 2×, so a town can bank study and spend it fast.
  - Research still runs one project at a time per town. Two at once would mean a second research slot in every town's save and on the Research page. The bank does the same job instead.
- **Grand Forge**
  - Smiths work 1.5× faster in every city.
  - Forge orders cost 25% less of each material (rounded up, never below one). The Blacksmith page still shows the full price; the discount is taken when you place the order.
  - **Masterwork.** Armour and melee weapons that a city's armoury hands to a recruit are Masterwork: +1 armour and +1 armour toughness on armour, +1.5 attack damage on weapons. They carry a gold "Masterwork · Grand Forge" line in the tooltip. The bonus is added on top of the item's own figures, so it works with modded gear too. Shields and bows get no bonus. The stockpile keeps plain items only, so an unworn masterwork piece goes back as a plain one when a kit changes. A worn one stays with its recruit, as any worn gear does. Resupply's spares are plain. Masterwork is for recruits only: a piece that ends up in a player's inventory, taken off a recruit or picked up from a fallen one, loses its mark within a second.
- **Grand Exchange**
  - Caravans sell for 1.5× (the sender's emeralds, or an NPC town's treasury).
  - Every city's treasury gains 6 emeralds a day.
- **Citadel of Heroes**
  - Recruits who finish training in any city of the kingdom start 2 levels higher (on top of Veterans) with one skill point (50 skill XP).
  - Every city's garrison cap rises by 25%, and by at least 4.
  - The kingdom may keep 2 more Heroes (see [heroes](heroes.md)).

## Showing it

The City Manager's **Kingdom** page has a **Wonders** card. It shows how many of the five the kingdom has built and which city is the capital. For each wonder it shows the effect and one state:

- **Locked**, with the reason (research, Capital rank, or "your kingdom has its … in …").
- **Available**, with its size, and a **Plan it** button for the ruler viewing the capital.
- **Building**, with a progress bar and where.
- **Built**, and where.

## Chronicle and quests

When a wonder is first seen standing, the town's chronicle gets a **Legend** entry ("The Grand Cathedral of Wondermere was completed, a wonder of the realm…"). Every other city of the kingdom records the news, and every player on the server sees a gold announcement. A wonder that changes hands is not announced again.

The last step of the quest line, *Long Live the King* (chapter The Crown), now also completes when the kingdom holds a wonder: "Take a rival kingdom's capital, rule 5 cities, or raise a Wonder".

## Losing one

A wonder is a building in its city. When a capital falls, `Kingdoms.transfer` hands the city and all its plots to the conqueror, and its wonders go with it. Their effects stop for the old kingdom and start for the new one at once, because each check asks which kingdom the wonder's city belongs to now. The conqueror cannot also build that wonder while it holds one. The old kingdom, once it has a new capital, may raise it again. A wonder in a city that is no longer the capital (after a conquest, or after the capital moves) keeps working.

## Testing

`./gradlew runServer -PwondersTest -PwondersTestDir=<fresh dir>` (copy `eula.txt` and `server.properties` from `run-military-test/`). It checks the catalogue and the blueprints in every palette, including that each lantern, banner, ladder, candle, door, bed and carpet has a block to hold it. It checks the research gate, the Capital-only and once-per-kingdom rules, and a real paid build of the Grand Cathedral to completion with its Legend entry. It checks every effect, measured in a second city of the kingdom: mood, sickness factors, research, the study bank, forge speed and cost, Masterwork attributes, armoury hand-out, muster levels, garrison cap, emeralds, caravan factor, and sparing on a real recruit (once a day, never outside the kingdom). It also checks the Kingdom page data, a save round trip, and transfer on conquest. It writes `wonders-test-result.txt`.
