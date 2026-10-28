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

## Not yet done

Walls and gatehouses are not planned automatically; tier-up repaving of laid streets, terrain-following (A*) street routes and City Map lot overlays are left for later.

## Tests

`./gradlew runServer -PcityPlanningTest -PcityPlanningTestDir=<fresh dir>` grows planned towns to City rank on synthetic ground (flat, slope with a hill, a river, a lake) and audits them with `PlanAudit`: hall central and facing south, every building facing its street within 3 blocks of the road, its street paved to its door and joined to the market place, no overlaps (including room to grow), districts kept, nothing in water, the same town every time. It then grows a town in the world and checks doors, roads at every door, street blocking, upgrades, off-screen lots and reload. `CityPlanningTour` (`-PcityPlanningTour`) photographs a grown town.
