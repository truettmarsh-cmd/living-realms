# Town planning

How automatic building lays out a town: `settlement/plan` (`TownPlan`, `Street`, `RoadClass`, `Zoning`, `PlanAudit`, `Terrain`). The design follows real medieval planned towns (market place at the crossing of the main roads, burgage plots fronting the streets, noisy and smelly trades at the edge or by the gates, fields outside) and city builders where every building touches a road.

## Skeleton

`TownPlan` is a pure function of the town centre, laid out to the Capital radius (160) so streets never move. Only paved lengths, surveyed sites, owed streets and each plot's street are saved (`Settlement.townPlan`, NBT `townPlan`).

| Street | Width | Where |
|---|---|---|
| Market place | 5 | A square ring 13 to 17 blocks out round the hall's precinct (±12) |
| High streets | 5 | North, south, east and west from the middle of each market-place side to the edge |
| Ring streets | 3 | Square rings 34, 62, 94 and 128 blocks out; each side leaves its high street both ways |

A street opens as far as its centre line lies two blocks inside `Settlement.radius()`. Paving material rises with the tier (`RoadClass.material`): path and gravel for camps, cobblestone and stone bricks for towns and cities, gravel in deserts.

## Lots

For each building, `TownPlan.place` tries both sides of every open street, every two blocks:

- The building is rotated so its front (`Blueprint.front`: the `building_entrance` jigsaw of a village template, else the wall nearest its doors; procedural buildings face +Z) faces the street.
- Its front wall stands 0 to 3 blocks off the road edge (by trade, with a little jitter), and the lot is sized for the building's largest level so it can be enlarged. `Planning.upgrade` keeps the front wall in place and grows the building backwards and to both sides (`TownPlan.grownOrigin`).
- The lot must lie inside the town's land, outside the market place and castle ring (±20; only market, tavern, chapel, library and decorations may front the market place, and not in the kingdom's capital), off every street of the skeleton, and clear of every other plot's grown footprint by the trade's side gap.
- Candidates are scored by district, street class and neighbours, then checked against the ground: loaded, dry, height spread at most 2 (3 for large footprints, 5 for mines, lumber camps, towers and chapels) and free of player builds.

The street is then paved from the market place to the lot (`TownPlan.connect`), parents first, so the road network is always connected.

## Districts (`Zoning`, distances from the centre, R = radius)

| Trade | Distance | Prefers |
|---|---|---|
| Town Hall | centre | middle of the market place, facing south |
| Market, tavern | 20 to 48 / 64 | high streets, market place |
| Chapel, library | 20 to 48 / 64 | near the market place |
| Storage | 20 to 80 | high streets |
| Houses | 20 + (inside out) | ring streets; not on high streets within 40 |
| Workshops | 24 + | tanners, butchers and dyers like smithies |
| Smithy, builder's yard | ≥ max(24, 0.45 R) | high streets |
| Stables, barracks | ≥ 24 (stables ≥ R/2), around 0.7 R | high streets |
| Watchtower | ≥ 0.75 R | the edge |
| Lumber camp, mine | ≥ max(32, 0.6 R) | the edge |
| Farm field | ≥ max(20, R/2) after the first two | the outskirts |

Houses lose score within 12 blocks of noisy trades.

## Other paths

- **Town Hall** goes in the middle of the market place with room to reach level 6; the market place and the first stretch of each high street are paved with it.
- **Off-screen.** While the town is loaded, `AutoBuild.survey` keeps four 17×17 lots along the streets on file (inner and outer). `away` plans a building on one that suits its district, facing its street, and owes the street until the ground loads.
- **Fallback.** With no fitting lot, an open site in the building's district is used, its door turned toward the centre, with a lane straight out of the door to the nearest road.
- **Manual plans** are unchanged, except that a building may not stand on a paved street (`Planning.checkSite`: "Blocks a street").
- **AI towns** use the same plan. Existing towns keep everything; new lots fit around their buildings.
- **Village Expansion** layouts replace the skeleton when present: see [village-expansion.md](village-expansion.md).

## Walls (`plan/Walls`)

A wall line is a square with chamfered corners round the centre: the columns with `max(|dx|-a, |dz|-a, |dx|+|dz|-b)` between -1 and 1 (three thick). Columns within 4 of a line (its band) never get lots, so the wall never has to cut through a building; ring streets stop two columns short of a line.

| Line | a | b | Comes at | Where |
|---|---|---|---|---|
| Old-town wall | 38 | 72 | Village + Fortifications | 3 outside the first ring street (34); chamfers of 4 |
| City wall | 98 | 174 | City | 3 outside the third ring street (94); chamfers of 22, inside the City radius |

Older circuits stay as inner walls with their gates open, the way towns that outgrew their walls kept them. Fields, lumber camps and mines go outside the old-town wall once a town is a Village (`Zoning`).

**Pieces**, in building order (each a paid project with a custom blueprint built from the ground it stands on):

1. **Gatehouses** where each high street crosses (the middle of each straight side): two 5 × 5 towers either side of a passage as wide as the street (5), paved with the street's material (a plank deck over water), clear for 4 blocks, with the wall walk on an arch above. The high street is paved out through the gate.
2. **Towers** at the corners (one in the middle of a short chamfer) and between them so no stretch is longer than 22 blocks (bow range, so each tower covers the wall face to the next). Towers project one block outward to flank the wall. Inside: a floor at walk height with openings onto the walk, a ladder on the town-side wall from the ground to the crenellated roof, and a door to the town.
3. **Wall** between them: stone from the ground (or the bottom of shallow water) to the walk, which is 5 above the highest ground across the wall and raised so it never steps more than one block between neighbouring columns. Merlons on the outer edge. Columns over water 3 or more deep, or where the ground falls 6 or more blocks outward (a cliff), are left open. Where a building the plan did not place is in the way, the wall bows outward round it (at most 12 blocks) and back to meet the next tower.

Wall pieces overlap-check by their real columns (a chamfer's box is mostly air), do not count as buildings for rank, and add one garrison place per tower or gate.

**With Village Expansion** (`vx/VxPlan.walls`), lines are fitted when a rank is reached: the old-town wall as tight as possible round every building except fields, woods, mines and outlying towers; the city wall at least 20 further out. Both stay clear of every building and avoid streets that run along them. Gatehouses go where VE's streets (laid or planned) cross; streets crossing elsewhere are paved up to the wall on both sides, and lots whose street would have to breach the wall are skipped.

**Player control.** City Map → Auto walls (per town, on by default) pauses automatic wall pieces and stops new ones. The City Map draws the planned lines dashed; the Architect view draws them on the ground. Player plans come first as with any automatic work.

**Raids.** When the attackers gather outside a wall, the defenders' hold point becomes the inside of the gatehouse nearest them (`Walls.gateFacing`), and the raid status says which gate they hold.

## Not yet done

Walls are not built for AI towns, there is no early timber palisade, and a Village Expansion street laid after the wall is fitted does not get a new gate. Tier-up repaving of laid streets, terrain-following (A*) street routes and City Map lot overlays are left for later.

## Tests

`./gradlew runServer -PcityPlanningTest -PcityPlanningTestDir=<fresh dir>` grows planned towns to City rank on synthetic ground (flat, slope with a hill, a river, a lake) and audits them with `PlanAudit`: hall central and facing south, every building facing its street within 3 blocks of the road, its street paved to its door and joined to the market place, no overlaps (including room to grow), districts kept, nothing in water, the same town every time. It then grows a town in the world and checks doors, roads at every door, street blocking, upgrades, off-screen lots and reload. Every synthetic town is also walled to City rank and checked by `PlanAudit.walls`: each line closed (except deep water and cliffs), every paved high street through an open, paved gatehouse, no piece on a building, street or the castle ring, no lot in a band, the walk never stepping more than one block, the same walls every time. In the world, the old-town wall is queued, paid and built at Village rank and its gates checked block by block. `CityPlanningTour` (`-PcityPlanningTour`) photographs a grown town.
