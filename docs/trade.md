# Trade and caravans

Towns make goods from their land and their workshops. Towns that are near each other and at peace keep trade routes, and a caravan sets out whenever one side has a surplus of something the other is short of. The code is in `settlement/Trade.java` (goods, prices, routes, the ledger), `settlement/Caravans.java` (caravans on the road, ambushes, events) and `client/ui/TradePanel.java` (the City Manager's **Trade** page). Everything is saved with the settlements under `trade`, and only when there is something to save, so older worlds load unchanged.

## Production

Every `Trade.STEP` (1200 ticks) each town makes goods from its biome family (`Procedural.styleFamily`). Rates are per settlement day, multiplied by `1 + population / 10`:

| Family | Goods a day |
|---|---|
| plains | 12 wheat, 4 bread, 3 white wool, 4 oak logs |
| taiga | 10 spruce logs, 2 leather, 3 rabbit hide |
| snowy | 6 spruce logs, 4 rabbit hide, 4 packed ice, 1 leather |
| desert | 16 sand, 6 glass, 6 sandstone, 2 yellow dye, 2 green dye |
| savanna | 10 acacia logs, 6 terracotta, 2 white wool |

Player towns' buildings already produce through `Simulation.produce` (farms, lumber camps, mines, smithies, workshops), so trade adds only the land's specialties. NPC towns and Village Expansion villages have no worked buildings in the simulation, so their standing buildings yield here: farms give wheat, bread and carrots; lumber camps give logs; mines give cobblestone, coal and some iron; smithies give iron ingots; stables give leather.

To keep towns from flooding, a good is not made once the town holds `SOFT_CAP` (192) of it. NPC towns also use a quarter of their trade goods a day, so their stores turn over and they keep wanting more.

## Need, surplus and price

A town **wants** (`Trade.wants`):
- what is still to be paid for on each of its building sites (the blueprint's bill, scaled by the work left), for player towns;
- the item cost of its current research;
- what every household keeps on hand: bread (2 per person), 16 + 2 per person of its own log, 4 + 1 per person of glass, and 4 + 1 per two people each of iron, leather and wool.

**Need** is what it wants minus what it has. **Surplus** is its stock minus its wants minus a reserve of 16; a good whose export the ruler banned has none, and emeralds are never exported.

**Price** (emeralds per unit) = base price × (wants + 32) / (stock + 32), held between a quarter and four times the base. A town short of glass pays more for it; a town sitting on 200 glass sells it cheap. Base prices go from 0.03 (cobblestone) to 1.0 (iron ingots); anything not in the list is worth 0.25.

## Routes

Each step, towns in the same dimension within `RANGE` (768 blocks) of each other form a route, each town with its nearest `MAX_ROUTES` (3). A route is open when the towns are:
- in the same kingdom, or
- of different kingdoms at peace: no war in the Statecraft ledger, no Village Expansion war between their factions (allied and neutral both trade), and no grievance (see below).

Either town's ruler can close a route from the Trade page, and reopen it. Routes to towns that were removed disappear.

## Caravans

On an open route without a caravan under way, and at most once every two steps, the side that did not send last tries first: it loads the goods the other town needs and it can spare, where the buyer's price is at least 20% above its own, best margin first, up to 96 goods. Fewer than 8 is not worth the trip. Goods already on their way to the buyer count against its need, so two caravans never fill the same shortage.

A caravan **loads** for 30 seconds, then goes **on the road**. It takes one or two guards: two from a town with 6 soldiers or more, one from a town with 2 or more (or an NPC town of Hamlet rank or above), none otherwise. Guards are hired for the trip; they are not taken from the garrison.

When the caravan **arrives**, the goods go into the buyer's stockpile, the seller earns half the cargo's value in emeralds (an NPC seller adds its full value to its treasury), and both towns gain prosperity. If it is **lost**, the goods are gone, or, if it fell in the world, they lie where it fell.

### On the road: walking or simulated

Where a player is within 64 blocks and the ground is loaded, a caravan is real: a wandering trader named for its town (it never despawns), leading a pack animal with a chest on a lead (a donkey from plains and desert towns, a llama from the others), with its guards as Recruits footmen walking with it. The trader walks from the market place of its town (15 blocks from the centre, on the side facing the other town) to the other town's market place, a dozen blocks at a time, using normal pathfinding. A trader stuck for 15 seconds hops a few blocks forward; one that takes three times as long as it should carries on simulated.

Elsewhere travel is simulated at 0.15 blocks a tick, about the walking pace. As with raids, a caravan is never lost because it unloaded: when its trader leaves loaded land, the caravan carries on simulated from where it was last seen, and its bodies are dropped as their chunks load again (every caravan body carries its caravan's id; one whose caravan is no longer walking never rejoins the world). A simulated caravan that comes into a player's view takes to the road for real again. On arrival its bodies leave the world.

## Danger

The chance that a trip meets an ambush is 10% per 500 blocks of road, plus 25% when the road passes within 256 blocks of a town at war with the caravan's kingdom (its raiders). Each guard takes a quarter off; an **escort** (soldiers of the caravan's own kingdom, or its ruler, walking within 16 blocks of a walking caravan) halves it. The chance is spread over the road, so it is rolled a little every second.

- **Walking**, an ambush is a real band: two pillagers (three against a guarded caravan), zombies at night (husks from desert towns), that go for the trader. The guards fight them.
- **Simulated**, the caravan is lost with a 60% chance, 20% less per guard (never below 10%). One that survives loses a quarter of its goods. A caravan is ambushed at most twice (once while walking).

Killing a walking caravan's trader or its pack animal ends the caravan and spills its goods on the ground. If the killer is a player, or a recruit, of another kingdom, it is a **hostile act** (`Trade.hostileAct`): trade between the two kingdoms is suspended for three days, and a second attack while that lasts opens a war in the Statecraft ledger. Raiders of a kingdom at war who destroy a simulated caravan count the same way.

## Prosperity

Every caravan that arrives adds 3 + value / 10 prosperity (0–100) to both towns; it fades by a tenth a day. A town with prosperity P builds `P / 10` percent faster (`Simulation.speed`, so both on-site builders and off-screen building) and grows by `P / 200` more a day (base growth is 1.5).

## The Trade page

The City Manager's **Trade** tab (after Kingdom) shows:
- prosperity, with the bonus it gives, and caravans sent, received and lost, and emeralds earned;
- each route: the partner, how the towns stand (same kingdom, allied, neutral), the distance, trips and losses, the latest caravan's status (loading, on the road with a progress bar and whether it is walking, arrived or lost and why), or why the route is shut; the ruler's toggle opens or closes it;
- the goods: stock, how many the town is short of, price (red when dear, green when cheap) and what it makes a day, with a button to ban or allow each good's export;
- recent caravans.

Actions: `tradeRoute` (`partner`, `open`) and `tradeBan` (`item`, `ban`), through `Actions` with the usual owner check.

## Self-test

`./gradlew runServer -PtradeTest -PtradeTestDir=<fresh dir>` writes `trade-test-result.txt`. It checks production by family and building and the soft cap; prices against supply and need; route formation, war and range; dispatch of exactly the goods short; a simulated caravan arriving at walking pace with goods, coin, prosperity and the growth and building bonus; export bans; ambush odds with guards, escorts and raiders; simulated ambush loss and survival; save and load of routes, caravans, bans and prosperity; hostile acts suspending trade and then starting a war; a caravan whose market is gone. Then in the world: a trader with a laden pack animal on a lead and a guard; a stale body culled on load; a trader leaving loaded land travelling on simulated, not lost; a real ambush band; killing the trader spilling the goods and counting as a hostile act; and a caravan walking 34 blocks to the other market by itself and delivering.

Screenshots of the Trade page (loading, then a caravan walking) and of a caravan on the road: `./gradlew runClient -PtradeUiTest -PrunDir=<fresh dir>` (`client/TradeUiTour.java`).

## Not done

Routes are not drawn on the City Map. Caravans travel in a straight line between markets (with pathfinding legs), not along paved streets. Caravans carry goods one way; the return trip is the next caravan on the route.
