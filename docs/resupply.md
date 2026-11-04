# Recruit resupply

Recruits restock their own arrows, food and gear out of combat, so an army needs less babysitting. Code: `livingrealms.supply` (`Resupply`, `SupplySource`, `Sources`, `SupplyData`).

## When

- Every loaded, owned recruit (not Workers-mod workers, not NPC kingdoms' soldiers) is looked at once every 100 ticks (5 seconds). Recruits are spread over those ticks by UUID, so a large army costs a handful of checks per tick.
- Only out of combat: no target, not in a Living Realms battle, not hurt in the last 10 seconds, not eating.
- Nothing happens while the recruit's **Auto resupply** switch is off.

## What

| Need | Rule |
|---|---|
| Arrows / bolts | Bowmen, crossbowmen and anyone holding a bow or crossbow. Below 48, top up to 64 of what the weapon shoots (plain arrows first; fireworks never). Crossbowmen's "bolts" are arrows in vanilla; a mod's bolt item works if its crossbow accepts it. |
| Food | Below 4, top up to 8 of the most filling food Recruits will eat. Nothing with effects (rotten flesh, spider eyes, golden apples) is taken. Villager Recruits' own eat goal eats from the pack when hunger is 50 or less, or 70 or less while hurt, and a fed recruit heals. A Living Realms town still feeds its garrison at home as before. |
| Worn gear | Weapon, shield or armour with 25% or less durability left. Plain gear is swapped for a spare of the same family (same armour slot, shield, or weapon kind: sword, axe, bow...) of the same or better tier (armour points, attack damage, durability for bows and shields); failing that, the town's smiths mend it. Enchanted or named gear is mended first and swapped only if it can't be. The worn piece goes into the recruit's pack, or back into the chest or your inventory if that is where the spare came from. |
| Broken gear | An empty slot that last held a weapon, shield or armour piece gets one of the same family, the best there is. Bowmen and crossbowmen with an empty hand also look for a bow or crossbow, shieldbearers for a shield. |

Mending needs a standing smithy in the town: one repair material (iron ingot for iron, diamond for diamond, planks for wood...) per quarter of the damage, as at an anvil.

## Where from, in order

1. **The town armoury**: the stockpile of the recruit's own Living Realms town, while the recruit is inside the town's land. Not for NPC towns or Village Expansion mirrors.
2. **Its upkeep storage**: the chest (or cart, chest boat) Recruits' upkeep command assigned, when loaded and within 48 blocks. The recruit's own pack is also searched first for a spare weapon or armour piece.
3. **Your inventory**: while you are online, in the same dimension and within 16 blocks, and only with **Allow from my inventory** on. Main inventory and hotbar, never the selected hotbar slot, your armour or off hand.

Each source is a `SupplySource` (`take`, `putBack`, `keepWorn`, `repair`). A Village Expansion stock source would be one more implementation added to `Resupply.sources`; Village Expansion has no per-recruit storage hook today, so none is used.

## Warnings

When a recruit is out of arrows or food, or needs a spare or replacement it can't get, its owner gets a gold chat line such as "Bowman Aldric is out of arrows and the armoury has none" or "... and neither the armoury nor your inventory has any". At most one every 3 minutes per owner, and none while the owner is offline or the recruit's switch is off. The Recruit Command Supply card shows what the recruit is short of, and the City Manager's garrison roster footer says "2 soldiers are low on supplies: arrows, gear".

## The screens

**Recruit Command → Overview → Supply**: ammo, food and gear (the most worn piece, or how many are broken) as bars, where the recruit can restock from right now, the last thing it took, and two switches:

- **Auto resupply**, per recruit, on by default.
- **Allow from my inventory**, per player (all your recruits), off by default.

## Saved data

`data/livingrealms_supply.dat`, apart from any town: per recruit UUID, the Auto resupply switch and the item ids it last carried in each slot (for replacing broken gear in kind); per player, whether they allow their inventory. A record is dropped when its recruit dies. Removing a Living Realms town does not touch it.

## Test

`./gradlew runServer -PsupplyTest -PsupplyTestDir=<fresh dir>` (copy `eula.txt` and `server.properties` in first) writes `supply-test-result.txt`. It covers the scheduler, the stockpile source and its refill marks, the per-recruit switch taking nothing, no resupply in combat, the owner's inventory behind its switch and range (and never the held stack), the upkeep chest, swaps only for the same or better tier, smithy repair, broken sword, axe and shield replacement, warning wording and throttling, and the saved data.
