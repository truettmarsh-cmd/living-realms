# Heroes

The recruit endgame. A town soldier who has finished its training can be raised up as a **Hero**: a named champion with a gold nameplate, its own perks, Valor levels past the top skill rank and its path's ultimate ability.

## Ascension

The **Ascension** card is at the foot of the **Path** page (Recruit Command, and the City Manager's soldier view) once the recruit has a path. It lists what is missing.

A recruit can be ascended when it has:

- a **legendary mastery**,
- **every skill point**: skill rank 18 (900 skill XP),
- and is **proven in battle**: 60 merit, or 10 battles survived.

A battle survived is a battle report (docs/battle-reports.md) in which the recruit fought and did not fall. Battles are counted from this version on; a recruit already in one of its owner's 20 kept reports gets credit for those too.

**Price**, paid once: 128 emeralds from the town's stock, and a Nether Star, or with Ice and Fire installed a fire, ice or lightning dragon heart instead. The rare item comes from your inventory first, then from town stock. Nothing is taken if anything is missing. The button asks for a second click to confirm.

**Cap:** one Hero for each city you hold (a settlement of rank City or Capital), at least 1 and at most 5. A Hero that is discarded for good (promoted through Villager Recruits' own screen) or falls frees its place. `Heroes.capBonus(owner)` is added on top of the cap; it is 0 for now, for later features such as a Citadel.

Ascension is announced to every player on the server ("✦ Bjorn the Titan of Ashford has ascended as a Hero."), written in the town's chronicle as a Legend, and shown with a column of light.

## Perks

| | |
|---|---|
| Name | Gold, always shown. A rename keeps the gold. Recruit Command's sidebar shows "Hero · Valor n". Gold sparks over its head every 2 seconds. |
| Health | +20% max health, healed in full on ascension |
| Knockback | None, from any source |
| Fear | Its morale never stays below 60, and it never panics (with the Recruits Morale addon) |
| Aura | Your recruits within 12 blocks with morale under 50 gain 5 morale every 5 seconds, and in raids they fall back at 20% health instead of 30% (45% when their morale is broken). The Hero itself holds the same way. |

## Ultimates

Each path has one ultimate. The Hero uses it on its own while it has a target, when the fight calls for it. Two minutes between uses (shared by nothing else). Each use is noted in the battle report among the Hero's advances ("Ultimate: Blade Storm"), and the owner sees a line above the hotbar if within 96 blocks.

| Path | Ultimate | Used when | Effect |
|---|---|---|---|
| Swordsman | Blade Storm | 2+ foes within 4 blocks, or a large foe within 3 | Spins for 4s: every half second, 3 damage to every hostile within 3.5 blocks |
| Shieldman | Bulwark | 3+ foes within 10 blocks, or it or a recruit within 6 blocks below half health | A 5-block dome for 8s where it stood: your recruits (and you) inside take 50% less damage; enemy projectiles entering it are stopped |
| Berserker | Earthshaker | 3+ foes within 6 blocks, or a large foe within 4 | 6 damage to every hostile within 6 blocks, hurled back and up, Slowness II for 5s |
| Spearman | Impale | Its target 4 to 12 blocks away in sight | Charges through the line toward it: 8 damage to every foe within 1.5 blocks of the line; the first large one (40+ health, wide, or riding) is pinned (Slowness VII, Weakness II) for 3s |
| Marksman | Rain of Arrows | Its target 8 to 40 blocks away with another foe within 4 blocks of it, or a large target | A volley over a 4-block circle around the target for 5s: 3 arrows (3 damage) every quarter second. They never hurt your side and can't be picked up |
| Knight | Royal Charge | Mounted (not on a dragon), its target 6 to 28 blocks away | It and your mounted recruits within 12 blocks charge: Speed III for their horses and +20% damage for 6s |
| Healer | Miracle | A recruit (or itself, or you) within 10 blocks below 30% health, or two below half | Full health, fire put out and every harmful effect removed for you and your recruits within 10 blocks. Once a battle: again only after 30 seconds out of combat, and the cooldown |

A "large" foe has 40+ max health or is wider than 1.2 blocks. Ultimate blows land even inside a target's hurt cooldown, and are not weapon hits, so they don't set off path splashes (Whirlwind), specials or weapon practice. They do count for kills, merit, bonds and battle reports.

## Valor

A Hero at skill rank 18 keeps earning combat XP, from the same sources, into **Valor**. Trickle XP (damage, alarms, escort) still counts against the soldier's 75-a-day cap.

| Valor | Total XP |
|---|---|
| 1 | 100 |
| 2 | 300 |
| 3 | 600 |
| n | 50 × n × (n + 1) |
| 10 | 5,500 |

Each level gives **+1% damage** and **+1% max health** (on top of the Hero's +20%). The Path page shows Valor as stars (★★★☆☆☆☆☆☆☆) with progress to the next level; the owner gets a chat line at each level.

## Budgets

Hero bonuses are their own terms, multiplied after every other budget (skills, weapons, legendary perks, paths, bonds), like the bond budget:

- **Damage: +30% at most** (`HERO_CAP`): Valor (up to +10%) and Royal Charge (+20%).
- **Reduction: 50% at most** (`HERO_DEFENCE_CAP`): Bulwark only.

Damage an ultimate deals itself (Blade Storm, Earthshaker, Impale, the Rain's arrows) is a fixed amount, raised only by Valor. The +20% health is a multiplier on the recruit's max health.

## A Hero's fall

When a Hero dies:

1. Every player on the server reads "✝ The hero Bjorn the Titan of Ashford has fallen, slain by a Zombie."
2. If it served in a town, the town's chronicle gets a Legend entry ("The hero Bjorn the Titan fell to a Zombie, after 12 days as a Hero and Valor 3. All the realm mourned.") instead of the usual Fallen line, and its name goes into the town's memorial (the People tab's "Remembered" list, in gold).
3. The weapon in its hand becomes an **heirloom**: named "Bjorn the Titan's Iron Sword", with a line of lore. The first town recruit armed with it gains **150 skill XP**, once (at the top rank that goes to Valor, if it is a Hero). The item keeps its name afterwards.

Its place under the cap is free again. Undying Rage and the Saint still save a Hero like any recruit.

## Storage and promotions

Heroes are saved per recruit UUID in `data/livingrealms_heroes.dat` (`HeroData`), not in the town, with each recruit's battles survived. A Hero keeps its name, epithet and path there, so dismissal from the garrison doesn't undo it (its ultimate still works; Valor needs a garrison, since it comes from skill XP). A promotion through Recruit Command moves the Hero to the new entity, like bonds and supply settings. A promotion through Villager Recruits' own screen discards the recruit: the Hero is then gone and frees its place.

## Test

`./gradlew runServer -PheroesTest -PheroesTestDir=<fresh dir>` (copy `eula.txt` and `server.properties` from `run-military-test/`). On real Villager Recruits soldiers it checks each requirement, the price and its payment from inventory or stock, the cap, every perk, the aura through BattleAI, Valor through real XP grants and the real hurt event, all seven ultimates through the real damage events with their cooldown, Miracle's once-a-battle rule, the battle report lines, a Hero's fall through a real death (message, Legend, memorial, heirloom), claiming the heirloom through a real equipment change, a promotion move and the save round trip. It writes `heroes-test-result.txt`.
