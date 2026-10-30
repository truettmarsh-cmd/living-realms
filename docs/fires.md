# Town fires

Wooden towns can burn; stone is what the upgrades are for.

## Risk

Each standing building has a risk from:

- **Material**: the share of its blueprint that burns (planks, logs, wool, thatch, anything vanilla fire can catch). A plains cottage is about 75% wood, a stone watchtower about 5%. Risk = 0.08 + 0.92 × wood share, so stone buildings are nearly safe.
- **Use**: smithies ×1.5 (forge sparks), taverns and workshops ×1.25, farms and decorations ×0.4. Roads and town walls never burn.
- **Crowding**: +15% per neighbouring building within 4 blocks (up to 6).

The town's risk is the sum, ×0.6 with a standing **Well**, ×0.5 with the **Fire Watch** research (Construction, after Masonry). The daily chance of an outbreak is `fires.dailyChance` (default 0.02) × min(3, risk / 8): a town of 8 all-wooden buildings has about one fire in 50 days. The City Manager's Chronicle page shows the risk (Very low / Low / Moderate / High), how many buildings are wooden, whether the town has a well and Fire Watch, and the three buildings most at risk.

## Causes

- The daily chance (a lamp, a hearth, a candle, the bread oven; sparks from the forge in a smithy). Season-free. Only in loaded towns.
- **Raids**: `fires.raidChancePerMinute` (default 0.05) while a town is being raided ("raiders' torches", or the besiegers' fire-pots when a siege weapons mod is installed).
- **Lightning**: a bolt in a town starts watching the strike area.
- **Lava, dragons and anything else** that makes vanilla fire: towns are checked one building every two seconds, and a fire found there is fought the same way (cause guessed from a recent strike, lava nearby or a dragon within 40 blocks).

No outbreaks start when the `doFireTick` gamerule is off.

## The fire

1. Up to three burnable blocks of the building catch real vanilla fire, the town bell rings and the owner is told.
2. Every half second the fire's area (the building plus 6 blocks, growing as the fire spreads, at most 56 × 32 × 56 and never past the town's land) is checked. Any fire within 2 blocks of the town's edge, or beyond it, is put out at once, so fire never leaves the town.
3. After `fires.brigadeDelay` ticks (default 200) the **bucket brigade** forms: the 4 nearest townsfolk (+2 with a well, +2 with Fire Watch) drop what they are doing and run to the fire; they and any of the town's soldiers standing near it each put out a fire block within reach every half second, two with a well or water nearby. If no townsfolk are loaded, unseen ones still pass buckets slowly.
4. After `fires.maxFireTicks` (default 6000) anything still burning is put out.

## Across restarts

A fire still burning when the world saves is written to `data/livingrealms_fires.dat` (`FireData`, its own saved data; nothing is added to the settlement save): the town, cause, start time, watched area, the buildings it has touched and its counters. On the next start it is watched again from the first look; the fire blocks are found again in the world and the bucket brigade re-forms round them (it forms at once if `brigadeDelay` has already passed since the fire started). A missing or damaged file loads no fires.

## Afterwards

Each building the fire touched is compared with its blueprint. If at least 2 blocks (or 1%) are gone, it is queued as a **repair** in Projects, and the builders put the missing blocks back from the stockpile, like any repair. The fire goes into the town log and, if anything was damaged, into the [chronicle](chronicle.md): small fires as "A fire started by ... damaged the bakery", three or more buildings (or a big blaze) as "The Great Fire of year N".

## Config (`livingrealms-server.toml`, `[fires]`)

| Key | Default | |
|---|---|---|
| `enabled` | true | Off: no outbreaks, no brigade, no containment; everything as before. |
| `dailyChance` | 0.02 | Base daily chance (see above). |
| `raidChancePerMinute` | 0.05 | Extra chance during raids. |
| `brigadeDelay` | 200 | Ticks before the brigade arrives. |
| `maxFireTicks` | 6000 | Fires are put out after this long. |
