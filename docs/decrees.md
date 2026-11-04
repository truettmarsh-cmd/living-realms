# Royal decrees

A kingdom of City rank can issue royal decrees: short, strong orders with a cost and a cooldown. They are on the **Royal decrees** card at the bottom of the City Manager's **Kingdom** page.

## Unlocking

- **Rank.** The kingdom's rank is the rank of its highest city. The first five decrees need a City, the last two a Capital.
- **Research.** **Royal Decrees** (Construction, 500 points, 16 paper and 4 gold ingots, after Urban Planning), researched in any city of the kingdom. War Horn and Call the Banners also need **Command** in any city.
- Only the ruler can issue a decree. Costs come from the capital's stockpile. "Food" is nutrition from plain food in the stockpile, the way the town itself eats (a loaf of bread is 5).
- A "day" is the settlement day (`simulation.dayLength`, 24000 ticks by default).

## The decrees

| Decree | Rank | Cost | Lasts | Cooldown | Effect |
|---|---|---|---|---|---|
| War Horn | City | 24 emeralds | 3 minutes | 1 day | Every recruit you own deals +15% damage, gains 20 morale at once and does not drop below 50 morale |
| Royal Feast | City | 32 emeralds, 2 food per person in the kingdom (at least 60) | 2 days | 4 days | +10 mood in every town, +4 loyalty a day in conquered cities; for the first half day sickness is 1.5x likelier (crowds) |
| Tax Holiday | City | none | 2 days | 3 days | No head tax in any town (merchants still pay); +8 mood, +3 loyalty a day |
| Royal Levy | City | none | 2 days | 3 days | Every town pays 1 emerald per person into its own stockpile at once; −10 mood, −3 loyalty a day |
| Amnesty | City | 96 emeralds, 8 gold ingots | 1 day | 3 days | Issued from a conquered city: its loyalty rises to at least 55, which ends unrest and any rebellion countdown. For a day NPC kingdoms weigh your peace offers +15 |
| Call the Banners | Capital | 48 emeralds + 4 a levy, 10 food a levy | 1 day | 5 days | Every town of 4 or more people sends militia levies to the city it is issued from: 1 from a Camp or Hamlet, 2 a Village, 3 a Town, 4 a City, 6 the Capital, at most 20 |
| Martial Law | Capital | 40 emeralds | while a city of yours is raided (at most 15 minutes) | 2 days | Siege breaches of your cities go 40% slower, the raided city's garrison heals 1 HP every 3 seconds, every town has −10 mood |

- Tax Holiday and Royal Levy can't overlap.
- Amnesty needs a conquered city (issue it from that city's page) or a war with an NPC kingdom. The peace bonus is added to the willingness score described in the README (Wars, peace terms).
- Martial Law can only be issued while a raid or siege on one of your cities is under way. It is lifted when the last one ends.

## War Horn and the buff budgets

War Horn is its own damage term, multiplied onto the hit after the skill, weapon, legendary, path and bond budgets are applied, and capped at +15%. It works for every recruit whose Villager Recruits owner is your kingdom: town soldiers and recruits hired the Recruits way. Once a second, any of your recruits below 50 morale is raised to 50. Morale goes through Villager Recruits' morale and, with the Recruits Morale addon, its battle morale too.

## Levies

- Levies are real Villager Recruits soldiers raised through `RecruitsBridge`: footmen, and every third one a bowman, with a stone sword or a bow and 24 arrows, a leather cap and tunic. They join the garrison of the city they muster at, hold its posts like its own soldiers and defend it in raids. Towns don't lose people for them.
- If that city is not loaded, the levies muster when it loads, as long as the decree lasts.
- After a day each levy goes home: it is removed if loaded, and its place in the garrison is freed. A levy in unloaded ground is removed when it next loads. Every levy carries its end time in its own saved data (`livingrealms_levy`), so one that loads after a restart, or one the decree data no longer knows, is removed as it loads too.
- A levy counts toward the garrison while it serves, so auto training waits until it leaves.

## NPC kingdoms

When an NPC kingdom (not a Village Expansion faction) raids, it sounds the War Horn if it is of City rank. When one is raided, it sounds the War Horn too, and at Capital rank it calls the banners to the city under attack. NPC kingdoms need no research. They pay 2 treasury per emerald of the cost from their seat, and the same cooldowns apply. Players at war with them see the announcement.

## Messages and the chronicle

Issuing a decree and its end are announced in chat with a toast, and written into every city's log. Call the Banners and Amnesty are also written into every city's chronicle.

## For other code

- `Decrees.active(kingdom, Decree.WAR_HORN)`: whether a decree is in force for a kingdom (a player's UUID, or an NPC kingdom's id).
- `Decrees.until(server, kingdom, decree)`, `Decrees.readyAt(server, kingdom, decree)`, `Decrees.isLevy(entity)`.
- State is kept in `data/livingrealms_decrees.dat` (`DecreeData`): per kingdom, the end time and cooldown of each decree and the city it was issued from, plus the levies, musters still waiting and levies to remove when they load.

## Test

`./gradlew runServer -PdecreesTest -PdecreesTestDir=<fresh dir>` (copy `eula.txt` and `server.properties` into the directory first) writes `decrees-test-result.txt`. It covers the rank and research gates, costs and refusals, cooldowns and expiry, every effect through the hooks the rest of the mod uses, War Horn on a real hit, levies mustering in loaded and unloaded towns and going home (loaded, unloaded, across a save and reload, and a stray levy the data forgot), and NPC decrees.
