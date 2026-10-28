# Kingdom AI

How NPC kingdoms (Living Realms' own AI towns) and Village Expansion (VE) village factions decide to fight, whom they attack, when they stop, and how they recover. The code is in `settlement/Strategy.java`, called from `Raids`, `Realms`, `Kingdoms`, `Simulation` and `AiPlanner`. Soldier-level behaviour inside a battle (targeting, formations, retreat, siege engines) is not covered here.

## Strength

Before this change no decision looked at armies. A kingdom's strength is now measured from Living Realms' soldier records:
- each soldier counts 1, plus 5% per Recruits level (up to +100%), scaled by its health (never below a quarter);
- a VE faction also counts its recruits that sit in unloaded villages (`VxBridge.recruitCount`);
- two sides compare as `(a + 1) / (a + b + 2)`. This is VE's scale (0.5 is even), smoothed so that two empty armies come out even instead of dividing by zero.

## Memory of recent fighting

Each kingdom keeps a small record in `SettlementManager.memory`, saved as `warMemory` only when there is something to save:
- **defeats.** +1 for a repelled or timed-out raid, +1.5 when no attacker came back, and +1 per city lost, whether to a raid or to a VE siege.
- **victories.** +1 per battle won.
- **attacked.** When the kingdom was last raided.
- **repelledBy.** Who threw it back, and when.

Defeats and victories fade by half each Minecraft day, and records that have faded away are pruned. Everything below reads this memory.

## War: VE factions declaring on player realms (`Realms.considerWars`)

VE cannot see Living Realms cities, which hold no claims, so Living Realms runs VE's player-claim war rule for them. The old version had two flaws.
- **It fed in VE's strength assessment.** A realm without claims has no VE military strength, so `relativeStrength` came out 1.0 and every faction got the maximum 85% war chance, whatever your garrison.
- **It only ever considered the nearest player city.** If that kingdom was already at war or under a truce, the faction did nothing.

Now (`Strategy.warChance`):
- A faction at least as strong as the realm gets VE's own odds exactly: 35% within 600 blocks, otherwise 18%, plus how much stronger it is, at most 85%.
- A weaker faction's base chance shrinks in proportion: at a ratio of 0.25 it is half. A faction with no troops facing 12 soldiers is under 6%.
- Recent defeats divide the chance by (1 + defeats), so a beaten faction is war-weary.
- A faction already at war with one realm is half as likely to open a second front.
- Every player kingdom in range is weighed, and the faction rolls once, for the kingdom it most wants to fight. A realm is only created for the kingdom actually chosen.

VE's own gates stay: relation must be NEUTRAL, no Living Realms truce, no VE wartime peace lock, and VE diplomacy must be switched on.

## Peace: losing factions sue for peace

Before this change, VE factions never sought peace with player realms. Only the ruler could ask, and VE's `aiWouldAccept` decided.

Now, on each war decision, a faction at war with a realm sues for peace when either holds:
- it is badly outmatched (ratio below 0.25);
- it is outmatched (below 0.4) and has at least one recent defeat.

It does not sue while a Living Realms raid between the two is under way, or while VE's wartime peace lock (`AssaultManager.isPeaceLocked`) holds for the pair.

The ruler's cities are told. The offer stands for one day, during which:
- that faction's villages do not march on the realm;
- the ruler's **Make peace** succeeds without asking `aiWouldAccept`, because the faction made the offer itself.

Peace still sets VE's pair freeze and Living Realms' one-day truce. Offers are not saved, like VE's own war decisions. A ruler who asks for peace without an offer still gets VE's `aiWouldAccept` answer.

## Raid targets (`Raids.npcTarget`)

Before, NPC kingdoms raided the nearest legal target. That could be a fortress that would kill a two-soldier expedition, or a hamlet next to an undefended capital.

Now every legal target is scored (`Strategy.raidScore`):
- **odds.** The expedition `Raids.start` would actually send (half the available soldiers, 2 to 12) against the target's garrison, plus 1 if its ruler is online. Targets with odds below 0.35 are never raided.
- **value.** 1, plus population / 24, plus 0.25 per rank. A capital is multiplied by 1 + 0.5 for each other city it would bring with it.
- **distance.** The score is divided by 1 + distance / raid range.
- **score.** odds² × value / distance factor, so winnability dominates.
- **revenge damping.** A kingdom that repelled this one in the last two days scores half, so a beaten army does not throw itself at the same walls.

Ties go to the nearer city. Every rule in `Raids.canStart` (war with VE villages, range, cooldowns, oaths, loaded chunks, three soldiers) still applies first.

## Regrouping after defeat (`Raids.tick`)

Before, a beaten kingdom marched again as soon as its one-day cooldown ended, with whatever was left: three soldiers were enough.

Now an NPC city needs 3 available soldiers plus 2 per recent defeat (at most 9) before it launches a raid (`Strategy.musterNeeded`). Defeats fade daily, so a beaten kingdom rebuilds or waits before it attacks again. This applies to VE villages raiding through Living Realms too. VE's own field armies keep VE's rules.

## Re-garrisoning and war footing (Living Realms NPC towns)

A town raided in the last two days is on a war footing:
- `Simulation.aiGarrison` raises its guard goal by half (at least two more) above its rank's usual size.
- Until it reaches that goal, `AiPlanner` starts no new building projects. AI buildings are paid in wealth, so the wealth goes to recruits first.

VE villages' garrisons are VE's to manage and are left alone.

## NPC research

AI towns used to pick a random open research, often military or smithing nodes that do nothing for them, while their next rank waited on Masonry or Architecture.

They now use the same priorities as a player town's auto research (`Strategy.aiResearch`):
1. the research chain for the next rank, once they have half its people and buildings;
2. then economy, construction and scholarship;
3. then the rest.

Within each group the cheapest comes first. AI research costs no items, so the item check is skipped.

## Left alone on purpose

- **VE-on-VE wars, VE field armies and sieges, VE's garrisons and auto-builder.** These are VE's own decisions.
- **Player-initiated war and peace requests.** VE's `aiWouldAccept` still answers when the faction has made no offer.
- **Battle behaviour once a raid is under way.** That is the soldier and battle AI's job: `Raids.tickRaid` orders, `RecruitsBridge`, `military/RecruitCombat`, `compat/SiegeTrains`.
- **Player town autonomy.** `Autonomy` and `AutoBuild` act for the player and follow their settings.

## Tests

- `WarfareSelfTest`, without VE, checks:
  - the odds formula against VE's;
  - weariness and the second-front rule;
  - when a faction sues for peace;
  - soldier weights;
  - memory decay and reload;
  - the war footing;
  - research order;
  - a nearer fortress being skipped for a weaker capital;
  - hopeless raids refused;
  - a beaten kingdom regrouping before it marches again.
- `VillageExpansionSelfTest`, with VE, checks:
  - outmatched factions keeping the peace;
  - a stronger faction fighting on;
  - a beaten faction suing for peace, not raiding meanwhile, and the ruler accepting.
