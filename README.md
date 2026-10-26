# Living Realms

A Forge 1.20.1 add-on for [Villager Recruits](https://www.curseforge.com/minecraft/mc-mods/villager-recruits). Towns grow on their own, you can found and plan your own city, and its villagers build it block by block, research technology, forge equipment and raise an army.

Living Realms is an original mod. It does not include or modify code from Recruits, Workers or Living Villages. It calls Recruits' public entity API and reads other mods' village structure files at runtime. A small optional Ice and Fire compatibility mixin seats owned recruit riders at the dragon's native saddle position instead of treating them as captured prey.

## Features

- **Founding a town.** Craft a **Town Charter** and place it to found a settlement. You get **Architect's Plans** and the City Manager opens (press **K** to reopen it).
- **City planning.** Choose a building in the Build tab, or use the Architect's Plans in the world. A translucent ghost shows where it goes: scroll to rotate it and shift-scroll to raise it. Roads are laid out by clicking from point to point, and scrolling changes their width. Settlers called *builders* walk to each site and place the blocks themselves.
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

## Requirements

- Minecraft 1.20.1, Forge 47+
- Villager Recruits 1.15+
- Optional: Epic Knights and its addons, Ice and Fire (and its required Citadel dependency), ChoiceTheorem's Overhauled Village, Towns & Towers

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

### Progression balance

Normal conditional damage bonuses share a +15% budget, favourite/mastery damage a +15% budget, and legendary bonuses a +10% budget (maximum +40% combined). This reserves useful headroom for legendary perks. All Living Realms percentage damage reductions share a 25% cap; native armour and Recruits attributes still apply normally. Second Wind heals only 2 HP with a 30-second cooldown, Battle Rhythm lasts 8 seconds with a 30-second cooldown, and Iron Will reduces a hit while already below 40% health with a 90-second cooldown. Legendary perks never prevent void or administrative kill damage.

Normal skill rank comes from the town-managed combat XP shown in the Skills tab. Legendary eligibility uses the recruit's native Villager Recruits level, shown separately in the Legendary tab. Weapon mastery has its own experience for each family.

### Recruit origins and optional sizing

New recruits roll a permanent Human, Dwarf, Elf or Giant origin, with configurable relative weights of 80/8/9/3. Dwarves gain 2 base health and lose 3% movement speed; elves lose 2 base health and gain 3% speed; giants gain 4 base health and lose 7% speed. These traits persist after leaving town service. Existing unmarked recruits remain human. Origins do not increase damage.

Install optional **Pehkui 3.8.2 for Forge 1.20.1** for visible height/width differences. Living Realms adjusts only HEIGHT/WIDTH, preserves external scaling factors, and leaves BASE damage/reach scaling alone. Origin traits and persistence work without Pehkui.

Development caveat: Pehkui's `ScreenHandlerMixin` fails in newer Forge development mappings (reproduced with 47.4.20), tracked in [upstream issue #614](https://github.com/Virtuoel/Pehkui/issues/614). The issue reports that production works and development Forge 47.4.0 avoids it. Use `-Ppehkui -Pforge_version=47.4.0` for the optional development test; this overrides only that invocation, not the project's normal Forge version. Production Forge 47.4.23 with Pehkui 3.8.2 passed the full integration suite, including 44 origin assertions.

### Capital castles

Capital-ranked settlements with Royal Charter automatically commission an original 41 × 41 courtyard castle at the village center. Villager builders use the existing paid construction system, pausing for missing materials. Four towers, a great hall, guard rooms and open gates surround an untouched 25 × 25 courtyard, preserving the charter and central buildings. Autonomous villages reserve the future perimeter. Existing towns report obstructing plots, blocks or uneven terrain in the Capital overview and town log; nothing is automatically demolished.

The layout is inspired by a published Bodiam Castle floor plan; see [blueprint source and placement details](docs/capital-castle.md). Capital rank is the trigger because Living Realms does not yet have a separate empire capital-election system. The integration test exercises paid completion, blocked sites, duplicate prevention, courtyard preservation and persistence.

Verification: the production jar passed 1,214 assertions with Epic Knights 10.15, Epic Knights Addon 2.6, Antique Legacy 1.13, Ice and Fire 2.1.13 and Pehkui 3.8.2 (1,141 smithing patterns), and 99 assertions with Recruits alone. Client checks verified promotion, following, mounted dragon escort and the skill/weapon/legendary/origin screens. Screenshots use staged demonstration progression. `runClient -PcastleUiTest` captures the completed castle in a new isolated world.
