# Living Realms

A Forge 1.20.1 add-on for [Villager Recruits](https://www.curseforge.com/minecraft/mc-mods/villager-recruits). Towns grow on their own, you can found and plan your own city, and its villagers build it block by block, research technology, forge equipment and raise an army.

Living Realms is an original mod. It does not include or modify code from Recruits, Workers or Village Expansion. It calls Recruits' public entity API, Village Expansion's faction, diplomacy and city-plan classes when that mod is installed, and reads other mods' village structure files at runtime. A small optional Ice and Fire compatibility mixin seats owned recruit riders at the dragon's native saddle position instead of treating them as captured prey.

## New in 0.4.0

- **Recruit Command (shift + right-click your recruit).** Overview with health, morale, both levels and quick orders (follow, hold, wander, rename); Promotions (Royal Guard plus Villager Recruits' Messenger, Scout, Siege Engineer, Patrol Leader and Captain, each with its requirement); Skills, Weapons, Legendary and Special pages; and the recruit's inventory. Recruits hired outside a town can **Enlist** in your nearest town. The Garrison list has Command and Inventory buttons, and an optional key commands the recruit under your crosshair.
- **Progression.** Combat XP also comes from damage dealt, surviving alarms and raids, defending, and Royal Guard escort (up to 75 XP a day). **Drill** spends emeralds and food for 40 XP plus Villager Recruits XP (10-minute cooldown); **Respec** refunds skills. Titles: Recruit, Soldier, Veteran, Sergeant, Elite, Champion.
- **Special skills.** 14 abilities recruits use on their own in battle (Shield Wall, Taunt, Rally Cry, Second Wind, Field Dressing, Stagger, Disarm, Riposte, Lunge, Cleave, Charge, Volley, Aimed Shot, Dragonfire Strafe), fitted to unit type and favourite weapon; slots at skill rank 3, 8 and 14.
- **Legendary perks.** 17 perks (5 new) and a pity counter: the first perk by Recruits level 14, the second by 21.
- **Honours and gear requests.** Military → Honours ranks your top 10 recruits by merit (kills, damage, raids, guard duty, survival; fades 15% a day). Recruits with merit 60+ ask for their favourite weapon or better armour; Grant it from stock, Order it from the smithy, or Decline.
- **Towns advance while you are away.** Auto build continues offscreen (blocks appear when the area loads), plus auto research, auto rank-up (keeps a 25% reserve) and auto training to a garrison goal, each switchable on Overview, with a "While you were away" report.
- **Architect view.** A free camera over your town (Build → Architect view, shift + right-click in the air with the plans, or its key): fly around, place full hologram buildings and roads with green/red validity, the bill of materials, and your queued projects shown as ghosts.
- **Living Realms Handbook**, a Patchouli guide given on first join (book + emerald to craft another).
- **Keys.** City Manager is now **O**; rotate, raise, lower, Architect view and Recruit Command are all rebindable in Controls.

## Features

- **Founding a town.** Craft a **Town Charter** and place it to found a settlement. You get **Architect's Plans** and the City Manager opens (press **O** to reopen it; every key can be changed in Controls).
- **City planning.** Choose a building in the Build tab, click **City Map**, and click the map to queue it without leaving the UI. Press **R** or use **Rotate** to change its orientation. You can also choose **In world** to place it with Architect's Plans. A translucent ghost shows where it goes: scroll to rotate it and shift-scroll to raise it. Roads are laid out by clicking from point to point, and scrolling changes their width. Settlers called *builders* walk to each site and place the blocks themselves.
- **Shared town stockpile.** Builders take materials from the town stockpile, so no chest needs to sit near the site. Put items in through the Storage tab or a **Delivery Barrel**, which feeds the stockpile.
- **Over 200 buildings.** About 20 hand-made building kinds (town hall, cottages, smithy, library, barracks, watchtowers, walls, gatehouse, farms, stables, tavern, chapel, market, mine and more) in plains, taiga, snowy, desert and savanna styles. Village houses from vanilla, ChoiceTheorem's Overhauled Village and Towns & Towers are picked up automatically when those mods are installed.
- **Upgrades.** Towns move up six tiers, from Camp to Hamlet, Village, Town, City and Capital. Buildings upgrade in levels, and a procedural building is actually rebuilt as its bigger next-level version.
- **Research.** A data-driven technology tree across five branches (Economy, Construction, Scholarship, Smithing, Military). It is paid for with research points from libraries plus some items, and it unlocks buildings, tiers, materials and unit types.
- **Blacksmith orders.** In City Manager → Military → Blacksmith, choose a piece or a full armour set, a material and an amount. Smithies forge the order from the stockpile. Which materials you can use depends on research: leather, chainmail, iron, gold, diamond and netherite. Equipment from installed Epic Knights addons and Ice and Fire is discovered automatically. Search by equipment name or mod ID; commission complete matched armour sets or individual pieces, including mount armour and Ice and Fire relic weapons. Relics are made for players; recruits use ordinary melee and ranged kits. Normal orders use a valid installed crafting/smithing recipe and its materials. Loot-only equipment has expensive Master Armourer commissions. Dragonsteel still requires real dragonsteel from Ice and Fire.
- **Military.** Train footmen, shieldbearers, bowmen, crossbowmen, horsemen and researched dragon riders at the barracks as real Recruits soldiers. Each unit type has a kit that sets what gear it receives. The garrison is fed and resupplied from the town. Stances are Patrol, Garrison, Muster, Follow and Free. Watchtowers raise an alarm when enemies come near. AI towns raise their own garrisons.
- **Dragon riders.** Research both Cavalry and Veteran Training, then Dragon Riders, build Stables, then train a mounted bowman. Bring one of your unoccupied, tamed stage 3+ Ice and Fire dragons within 48 blocks of the barracks. Training waits at completion until a suitable dragon is present; cancelling refunds the training cost. Dragons retain their owner and use Ice and Fire's native escort movement when the rider follows you. No free dragons are spawned. Fire, ice and lightning dragons are supported.
- **Royal Guards.** Military → Garrison → Skills lets you promote an existing recruit after Command research, recruit rank 2 and 32 emeralds. Personal guards follow and protect their owner independently of town stances and alarms. They hold position if the owner is offline or in another dimension; no chunks are force-loaded. Return them to town duty from the same screen.
- **Recruit skill tree.** Each recruit earns 25 XP per hostile kill and one skill point every 50 XP, up to 18 points. Eighteen normal skills across Defence, Combat and Support unlock at skill ranks 1, 2, 4, 7, 11 and 16. Skills cover base health, armour/toughness, knockback, shields, melee/projectile damage, target conditions, movement, scouting, fire/blast protection and out-of-combat recovery. Native Recruits modifiers may scale base attribute bonuses. Dismissal removes town-managed skill bonuses.
- **Favourite weapons and practice.** Every soldier has a stable weapon preference: swords, greatswords, daggers, spears/polearms, axes, maces/hammers, bows or crossbows. Using it gives +5% damage and +25% practice XP. Actual damage to hostile creatures and kills train only the weapon family used; arrows retain their launch family even if gear changes before impact. Five mastery ranks at 100/300/600/1,000/1,500 XP add +2% damage each. Weapon material upgrades preserve practice. Check Military → Garrison → Skills → Weapons.
- **Legendary veteran perks.** Starting at native Villager Recruits level 8, each newly reached level has a 12% chance of one of 12 legendary perks. A second slot opens at level 14; at most two unique perks. Rolls, results and cooldowns are saved, so reopening the screen or reloading does not reroll. These are modest, contextual effects such as preferred-weapon damage, long-range shots, spear-and-shield combat, brief post-kill damage, limited recovery, protection near the owner, fire protection, knockback resistance, scouting or faster practice. No invulnerability or resurrection.
- **Living AI villages.** Naturally generated villages become AI settlements. They plan, build and grow by themselves, even while nobody is nearby, catching up when their chunks load again.

## Plan your city and let villagers build

In **City Manager → Build**, choose a building and select **City Map**. The footprint follows your cursor; click clear terrain to queue the building, press **R** to rotate, and right-drag to pan while placing. Scroll zooms. You can queue several buildings without closing the UI. The server checks permissions, terrain, territory, research and overlapping plots. **Done planning** returns the map to its normal selection mode. **In world** retains the existing Architect's Plans workflow; road planning remains available through those plans.

**Overview → Auto build** is on by default, including in existing towns. When no construction, road, repair, upgrade or demolition is queued, villagers choose one affordable building that fits the town's needs and find a clear site. They can start with the Town Hall. The project appears in the usual map and project list and is built block by block using town materials. Villagers do not automatically claim ranks or choose your research.

Your queued projects always come first. Adding a manual project pauses an automatic project, including work already assigned to a builder and offscreen construction payments. Automatic work resumes after your queue finishes. Turning **Auto build** off pauses existing automatic projects and prevents new ones, while your manual queue continues. Settings and project priority persist through saves. Manual plans can still be queued before all their materials are stocked; autonomous planning waits until the full building and foundation bill is affordable.

## Kingdoms, capitals and conquest

All cities owned by the same player form one kingdom. Independent NPC towns start as separate kingdoms and can expand through conquest. Each kingdom has exactly one designated capital, starting with its first town; this designation is separate from the town's development rank. Only that settlement can reach Capital rank or commission a Capital Castle. City Manager → Overview shows the capital and kingdom city count. **Make capital** moves the designation, with a one-Minecraft-day cooldown and no moves during a raid. A former Capital-rank settlement returns to City rank, retaining its buildings, stockpile and research.

Open a rival city's City Manager and choose **Raid this city**. The server selects your nearest eligible town and sends half its available foot soldiers (2–12), leaving the rest at home. The source needs at least three living, unmounted soldiers in its territory; personal Royal Guards and mounted units stay out of the expedition. Both cities must be in the same dimension and within 1,024 blocks by default. Soldiers travel physically: the cities and armies must remain loaded. Unloaded soldiers pause the raid rather than count as dead. No chunks are force-loaded.

Defeat the local defending recruits and any defending ruler present in Survival, then keep at least one attacker within 12 blocks of the city center, uncontested, for 60 seconds. Contested occupation resets the timer. Killing the expedition defeats the raid; an expedition that cannot capture the city within ten active minutes retreats.

In the fight, shieldbearers screen the archers, archers hold at bow range, and melee soldiers spread over the enemy line and finish the wounded. Badly wounded soldiers fall back to bind their wounds. Outnumbered defenders close ranks around the city centre. Siege engines lift their fire once the attackers reach the centre, and Royal Guards cut down raiders who close on their ruler. See [docs/battle-ai.md](docs/battle-ai.md).

- **Ordinary city victory:** transfers only that city.
- **Capital victory:** transfers every city of the defeated kingdom, including cities in other dimensions. The winner retains its own capital; the captured capital becomes an ordinary city.
- Ownership, stockpiles, buildings and research transfer together. Surviving town soldiers change allegiance, including when they next load. Personal guard status is cleared; captured riders dismount rather than transferring ownership of a player's dragon or horse.

NPC kingdoms periodically choose eligible rivals, including online players, preferring weakly held, valuable and nearby cities and refusing hopeless attacks, and send real existing garrison soldiers. A beaten kingdom regroups before it marches again, and a raided NPC town raises a larger guard (see [docs/kingdom-ai.md](docs/kingdom-ai.md)). A warning announces the attack and whether the capital—and therefore the whole kingdom—is at stake. Newly discovered NPC kingdoms wait one Minecraft day before initiating raids. Kingdoms handle one raid at a time and recover for a day afterward. Raid state and occupation progress survive saving and restarting.

Server config `warfare.npcRaids` enables NPC attacks (default true); `raidRange` and `raidCooldown` control range and recovery. City Manager displays the current battle or pause status. Existing worlds migrate automatically: one existing capital is retained per kingdom, or the oldest town becomes the seat when none was designated. Extra Capital-rank towns retain their buildings and become cities.

## Requirements

- Minecraft 1.20.1, Forge 47+
- Villager Recruits 1.15+
- Optional: Villager Recruits: Village Expansion, Epic Knights and its addons, Ice and Fire (and its required Citadel dependency), ChoiceTheorem's Overhauled Village, Towns & Towers

## Works with your pack

Living Realms ties into the other kingdom mods of a Villager Recruits pack when they are installed; with any of them missing, nothing changes. The City Manager Overview shows a **Pack** strip (hover for details), and each part has a switch under `compat` in `serverconfig/livingrealms-server.toml`.

| Mod | What it does in your kingdom | Switch |
| --- | --- | --- |
| Vassal & Suzerain | The Recruits faction you lead is titled by kingdom size: King (3 cities or a Capital), Duke (2 cities or a City), Count. A vassal sits one rank below its liege. Liege and vassal kingdoms cannot raid each other, and a liege hears when a vassal's city is raided. Titles you set by hand are left alone. | `feudalTitles`, `feudalOaths` |
| Ranks and Titles | Your faction's Leader title (tab list) shows the same rank, unless you renamed it. | `rankTitles` |
| Siege Weapons, Medieval Siege Machines (+ Recruits Siege Compatibility), Kingdom Sieges | The Blacksmith gets a War machines tab. A raid takes one engine from the attacking city's stockpile; it sets up outside the target with a crew and ammunition and bombards the centre (a Recruits Siege Engineer for Siege Weapons, a bowman for Siege Machines). A surviving engine returns to the stockpile. NPC kingdoms of City rank bring one too. | `siegeTrains`, `npcSiegeTrains` |
| Small Ships | Cogs, briggs, galleys, drakkars and ship cannons on the War machines tab; Captain promotion in Recruit Command. | |
| Siege Overhaul | Its raiders (soldiers, not monsters) ring the town alarm and the garrison engages them. | `raiderAlarm` |
| Recruits Morale addon | Granting (+10) or declining (−8) a request also moves battle morale; veterans get +5 max battle morale per rank (up to 150). | `morale` |
| Workers | Your Workers-mod workers inside a town fill its matching jobs (farms, lumber camps, mines, builders, markets, couriers, inns), freeing settlers. | `workersStaffTowns` |
| Corpse | After a fight in town, unused gear on your fallen soldiers' corpses returns to the armoury and the body is laid at the town centre with its worn gear. Soldiers who fall abroad are noted with where the body lies. | `corpseSalvage` |
| Knights Heraldry | Its armour and weapons become smithing patterns and kit choices. | `heraldryGear` |

Commanders Vision and Recruits Extras need nothing extra: town soldiers are ordinary recruits you own, so they are highlighted and commanded like any other.

To test with the pack, put the pack's jars in `libs/pack/` (see `build.gradle`) and run `./gradlew runServer -PcompatTest -PcompatTestDir=<dir> -PpackMods`; without `-PpackMods` the same test checks that every integration stays inactive. `-PpackMods` also works with the other test flags.

## Village Expansion

With **Villager Recruits: Village Expansion** installed, its villages are the NPC kingdoms, and Living Realms stops turning generated villages into its own AI towns. This is switched by `villageExpansion.enabled` (default true). Without the mod, or with the switch off, nothing changes. Design notes are in [docs/village-expansion.md](docs/village-expansion.md).

- **Their villages.** Each Village Expansion village shows up as a city you can open in the City Manager. Living Realms never simulates, builds on or garrisons it; Village Expansion runs it.
- **Your realm.** Your kingdom fights under a Recruits faction: your own, or one created for you and led by you. It takes part in Village Expansion's diplomacy as a real faction. **Military → Realms** lists the factions within raid range: their status (at war, neutral or allied), their villages and soldiers, and buttons to **Declare war**, **Make peace** (they must agree; a one-day truce follows) and **Raid**.
- **War.** Nearby factions may declare war on you with Village Expansion's own odds. At war, their villages march on your cities with their own recruits, and you can raid their villages.
- **Conquest.** Capture follows Living Realms' rules: defeat the defenders, then hold the centre for 60 seconds. A village you take becomes your city, with its claim, garrison and plan moved over in Village Expansion too. Take their capital and every village of the faction joins you. Lose your capital and every city of yours becomes one of their villages. Recruits sieges that Village Expansion resolves follow the same rule.
- **City planning.** Auto build uses Village Expansion's city planner for your towns. Streets run out from the centre, with lots zoned for houses, workshops, barracks, farms, mines and forges. Villagers put Living Realms buildings on matching lots and queue the street in front. The layout grows with your population. Your own plans still come first, and the City Map, Architect view and manual queue work as before.

Test with `./gradlew runServer -PvillageExpansionTest -PvillageExpansionTestDir=<dir> -PvillageExpansion`. It needs `village_recruits-*.jar` in `libs/pack/`. Without `-PvillageExpansion`, the same test checks that the integration stays inactive.

## Data packs

These folders can be overridden or extended with data packs:

- `data/<ns>/livingrealms_research/*.json`: research nodes.
- `data/<ns>/livingrealms_smithing/*.json`: smithing patterns (material, pieces, costs, required research and `requires_mod`).
- `data/<ns>/livingrealms_buildings/*.json`: tweaks to auto-discovered buildings, keyed by `"template"` (name, category, tier, research, or `"hide": true`).

## Building from source

Put `recruits-1.15.2.jar` in `libs/`; it is not redistributed. Then run:

```
./gradlew build
```

`./tools/tour.sh` runs an automated in-game test. It founds a town, plans and builds it, trains soldiers, and saves screenshots to `run/screenshots`.

## License

MIT

## Equipment integration

Loaded mod dependencies identify Epic Knights addons, including addons of addons; mods whose display name contains “Epic Knights” are also included. Equipment is classified by its registered item type and combat attributes. Discovery runs on server startup and data-pack reload. Existing hand-authored patterns remain available for save compatibility. Dynamically generated patterns use stable `equipment/<namespace>/<item>` IDs.

For ingredients accepting several items, the catalogue displays one concrete accepted item rather than an ambiguous overlapping tag. Deposits accept new unmodified equipment, but keep damaged, named, enchanted and otherwise customised stacks out of the item-ID-only stockpile. Custom NBT recipe requirements are not flattened; explicit smithing patterns can provide additional specialised support.

Research adds Mythic Smithing, Dragonsteel Forging and Master Armourer. Commissions for equipment without a supported ordinary recipe require four netherite ingots, eight diamonds, 32 emeralds, applicable repair materials, and dragon bones for Ice and Fire equipment. These are Living Realms commissions, not replacements for the source mods' crafting recipes.

## Military integration verification

Use JDK 17 and Gradle 8.8. `-Pintegrations` loads the optional local jars named in `build.gradle` from `libs/`; none are redistributed. `runServer -PmilitaryTest` runs assertions in a separate `run-military-test/` server directory and writes `military-test-result.txt` there. Add `-Pintegrations` to exercise Epic Knights, its Addon, Antique Legacy and Ice and Fire together. The test server needs its own EULA file and localhost-only `server.properties`; it never touches the normal `run/` world. The assertion result file, not merely Gradle's exit code, determines whether the tests passed.

Run `sh tools/warfare-test.sh --offline` for the isolated kingdom and conquest suite. Set `GRADLE_BIN` to a Gradle 8.8 executable if the local wrapper uses another version, and `WARFARE_EULA_FILE` to an existing accepted server EULA file if needed. The script prints its temporary instance directory and checks the assertion report before returning success.

Version 0.3.0 verification: the packaged jar passed 41 warfare assertions on Forge 47.4.23 with Recruits 1.15.2. The existing development integration suite passed 1,202 military, progression, castle, smithing and dragon assertions with Epic Knights, its Addon, Antique Legacy and Ice and Fire.

### Progression balance

Normal conditional damage bonuses share a +15% budget, favourite/mastery damage a +15% budget, and legendary bonuses a +10% budget (maximum +40% combined). This reserves useful headroom for legendary perks. All Living Realms percentage damage reductions share a 25% cap; native armour and Recruits attributes still apply normally. Second Wind heals only 2 HP with a 30-second cooldown, Battle Rhythm lasts 8 seconds with a 30-second cooldown, and Iron Will reduces a hit while already below 40% health with a 90-second cooldown. Legendary perks never prevent void or administrative kill damage.

Normal skill rank comes from the town-managed combat XP shown in the Skills tab. Legendary eligibility uses the recruit's native Villager Recruits level, shown separately in the Legendary tab. Weapon mastery has its own experience for each family.

### Recruit origins and optional sizing

New recruits roll a permanent Human, Dwarf, Elf or Giant origin, with configurable relative weights of 80/8/9/3. Dwarves gain 2 base health and lose 3% movement speed; elves lose 2 base health and gain 3% speed; giants gain 4 base health and lose 7% speed. These traits persist after leaving town service. Existing unmarked recruits remain human. Origins do not increase damage.

Install optional **Pehkui 3.8.2 for Forge 1.20.1** for visible height/width differences. Living Realms adjusts only HEIGHT/WIDTH, preserves external scaling factors, and leaves BASE damage/reach scaling alone. Origin traits and persistence work without Pehkui.

Development caveat: Pehkui's `ScreenHandlerMixin` fails in newer Forge development mappings (reproduced with 47.4.20), tracked in [upstream issue #614](https://github.com/Virtuoel/Pehkui/issues/614). The issue reports that production works and development Forge 47.4.0 avoids it. Use `-Ppehkui -Pforge_version=47.4.0` for the optional development test; this overrides only that invocation, not the project's normal Forge version. Production Forge 47.4.23 with Pehkui 3.8.2 passed the full integration suite, including 44 origin assertions.

### Capital castles

Capital-ranked settlements with Royal Charter automatically commission an original 41 × 41 courtyard castle at the village center. Villager builders use the existing paid construction system, pausing for missing materials. Four towers, a great hall, guard rooms and open gates surround an untouched 25 × 25 courtyard, preserving the charter and central buildings. Autonomous villages reserve the future perimeter. Existing towns report obstructing plots, blocks or uneven terrain in the Capital overview and town log; nothing is automatically demolished.

The layout is inspired by a published Bodiam Castle floor plan; see [blueprint source and placement details](docs/capital-castle.md). The designated kingdom capital must also reach Capital rank to trigger construction. Other cities cannot commission a new Capital Castle. The integration test exercises paid completion, blocked sites, duplicate prevention, courtyard preservation and persistence.

Version 0.2.0 verification: the production jar passed 1,214 assertions with Epic Knights 10.15, Epic Knights Addon 2.6, Antique Legacy 1.13, Ice and Fire 2.1.13 and Pehkui 3.8.2 (1,141 smithing patterns), and 99 assertions with Recruits alone. Client checks verified promotion, following, mounted dragon escort and the skill/weapon/legendary/origin screens. Screenshots use staged demonstration progression. `runClient -PcastleUiTest` captures the completed castle in a new isolated world.
