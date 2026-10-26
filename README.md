# Living Realms

A Forge 1.20.1 add-on for [Villager Recruits](https://www.curseforge.com/minecraft/mc-mods/villager-recruits). Towns grow on their own, you can found and plan your own city, and its villagers build it block by block, research technology, forge equipment and raise an army.

Living Realms is an original mod. It does not include or modify code from Recruits, Workers or Living Villages. It only calls Recruits' public entity API, and it reads other mods' village structure files at runtime.

## Features

- **Founding a town.** Craft a **Town Charter** and place it to found a settlement. You get **Architect's Plans** and the City Manager opens (press **K** to reopen it).
- **City planning.** Choose a building in the Build tab, or use the Architect's Plans in the world. A translucent ghost shows where it goes: scroll to rotate it and shift-scroll to raise it. Roads are laid out by clicking from point to point, and scrolling changes their width. Settlers called *builders* walk to each site and place the blocks themselves.
- **Shared town stockpile.** Builders take materials from the town stockpile, so no chest needs to sit near the site. Put items in through the Storage tab or a **Delivery Barrel**, which feeds the stockpile.
- **Over 200 buildings.** About 20 hand-made building kinds (town hall, cottages, smithy, library, barracks, watchtowers, walls, gatehouse, farms, stables, tavern, chapel, market, mine and more) in plains, taiga, snowy, desert and savanna styles. Village houses from vanilla, ChoiceTheorem's Overhauled Village and Towns & Towers are picked up automatically when those mods are installed.
- **Upgrades.** Towns move up six tiers, from Camp to Hamlet, Village, Town, City and Capital. Buildings upgrade in levels, and a procedural building is actually rebuilt as its bigger next-level version.
- **Research.** A tree of 35 technologies across five branches (Economy, Construction, Scholarship, Smithing, Military). It is paid for with research points from libraries plus some items, and it unlocks buildings, tiers, materials and unit types.
- **Blacksmith orders.** In City Manager → Military → Blacksmith, choose a piece or a full armour set, a material and an amount. Smithies forge the order from the stockpile. Which materials you can use depends on research: leather, chainmail, iron, gold, diamond and netherite. With Epic Knights installed you also get steel, knight, crusader, gothic and Maximilian sets.
- **Military.** Train footmen, shieldbearers, bowmen, crossbowmen and horsemen at the barracks as real Recruits soldiers. Each unit type has a kit that sets what gear it receives. The garrison is fed and resupplied from the town. Stances are Patrol, Garrison, Muster, Follow and Free. Watchtowers raise an alarm when enemies come near. AI towns raise their own garrisons.
- **Living AI villages.** Naturally generated villages become AI settlements. They plan, build and grow by themselves, even while nobody is nearby, catching up when their chunks load again.

## Requirements

- Minecraft 1.20.1, Forge 47+
- Villager Recruits 1.15+
- Optional: Epic Knights, ChoiceTheorem's Overhauled Village, Towns & Towers

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
