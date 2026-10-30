# Quests and town bounties

A quest line that walks a player from a first charter to a crown, and a bounty board in every town. Code is in
`livingrealms.quest`; the page is `client/ui/QuestPanel.java`, the toast `client/QuestToast.java`.

## The quest line

Five chapters, 26 steps, in `data/livingrealms/livingrealms_quests/crown.json`:

| Chapter | Steps |
| --- | --- |
| A Charter and a Hearth | found a settlement · finish a Town Hall · 4 buildings in one town · 3 soldiers · Hamlet |
| Stone and Scholarship | a Library · 3 research in one town · Masonry · complete a bounty · Village |
| Walls and Watchmen | Fortifications · 4 wall pieces · 8 soldiers · 20 hostile kills · hold off a raid (or take a city) · Town |
| The Realm Widens | 2 cities · lead a raid · a truce or tribute · a Royal Guard · City |
| The Crown | Royal Charter · Capital rank · the Capital Castle · 3 cities · take a rival capital, or rule 5 cities |

- **Checking.** Every 2 seconds each online player's current chapter is checked against their kingdom (every city they
  own): plots, rank, research, soldiers, Statecraft truces and tribute, Village Expansion treaties. A few things are
  counted from events instead: hostile mobs killed by the player or their town soldiers (`LivingDeathEvent`), raids
  held off and raids led (`Raids.finish`), cities and capitals taken (`Raids.tickRaid`, before the city changes hands).
  These counters run from the start, so a raid held in chapter 1 already counts in chapter 3.
- **Order.** Only the current chapter's steps can complete; steps within it complete in any order. When a chapter is
  done the next opens in the same check, so a later goal already met completes at once, after the earlier chapter.
- **Rewards once.** A step is rewarded at the moment it is added to the player's `done` list, which is saved; the list
  is never re-rewarded. Emeralds and items go to the player (dropped at their feet when the inventory is full);
  `stock` goes to the stockpile and `research` to the banked research points of the kingdom's capital; `merit` is added
  to every soldier of the kingdom (Honours).
- **Notification.** A chat line, an advancement-style toast and a level-up chime; finishing a chapter plays the
  challenge fanfare.
- **Saving.** `QuestData`, its own saved data (`data/livingrealms_quests.dat` in the world), keyed by player UUID:
  completed steps in order, counters, announced chapters. Nothing is added to the settlement save. Missing data
  loads empty; a damaged bounty is dropped.

### Data pack format

Files in `data/<ns>/livingrealms_quests/*.json` are read in path order and their chapters appended; override
`livingrealms:livingrealms_quests/crown.json` to replace the line. Step ids are `chapter/step`; changing them resets
those steps for existing players.

```json
{"chapters": [{"id": "hearth", "title": "...", "intro": "...", "icon": "minecraft:campfire", "steps": [
  {"id": "hall", "title": "...", "flavour": "...", "objective": "...",
   "check": {"type": "building", "id": "livingrealms:town_hall", "count": 1},
   "reward": {"emeralds": 4, "items": {"minecraft:bread": 8}, "stock": {"minecraft:cobblestone": 32}, "research": 30, "merit": 10}}]}]}
```

Check types (`count` defaults to 1): `cities`, `tier` (rank number, Camp 1 … Capital 6), `building` (`id`, standing
plots across the kingdom), `category` (`id` a building category such as `library`), `buildings` (finished buildings
in one town, as ranks count them), `soldiers`, `research` (`id`), `research_count` (in one town), `walls` (wall
sections, towers, gatehouses), `kills`, `battles` (raids held + cities taken), `raids_led`, `cities_taken`,
`bounties`, `peace`, `royal_guard`, `castle`, `crown` (a rival capital taken, or `count` cities). An unknown type is
logged and never completes.

## Town bounties

Every town that Living Realms runs (not Village Expansion villages) keeps up to three bounties, filled when the board
is looked at:

- **Deliver** what its projects still need beyond the stockpile, the item a builder is waiting for first (from the
  plot's "Waiting for …" status and each pending plot's remaining bill of materials). With nothing short: bread when
  the granary is below 4 food per person, else stores for its rank (logs, cobblestone, iron). 16 to 32 + 32 × rank,
  in eights. **Deliver** takes plain stacks from the player's inventory into the stockpile; partial deliveries count.
- **Slay** 4 + 2 × rank hostile mobs within the town's radius + 32 blocks, by the player or their town soldiers
  (counted while the player is online).
- **Patrol** four posts at the town's edge (N/E/S/W, radius − 6): stand within 8 blocks of each. The page names the
  next post.

Rewards are emeralds: delivery rank + amount / 32, slay 1 + 2 × rank, patrol 1 + rank. A player holds at most three
bounties, from any towns not at war with them. Bounties last three game days (`dayLength`); taking one gives it at
least a day. After a bounty leaves the board the town waits a quarter day before posting another. Bounties taken
elsewhere are listed under the board and can be delivered or abandoned from any town's page.

## City Manager

**Quests** is the last page: chapter pips, the current chapter with each step's progress bar (`3/5`), objective and
reward (hover for the flavour line), then the town's bounty board with Accept / Deliver / Abandon.

## Test

`./gradlew runServer -PquestTest -PquestTestDir=<fresh dir>` writes `quest-test-result.txt` (200 assertions): the
shipped line loads, a player is walked through all five chapters by changing settlement state, steps complete only in
their chapter and in order, every reward is paid once (total emeralds checked), raid and kill hooks, save/load round
trips (also of empty and damaged data), and bounty generation from builder shortages and hunger, rank scaling,
delivery, slaying, patrol, the three-bounty limit, the posting gap and expiry.

`./gradlew runClient -PquestUiTest -PrunDir=<fresh dir>` (QuestUiTour) seeds a town that has met chapter 1, takes two
bounties and screenshots the toasts and the Quests page at two GUI scales.

## Not done

- No "clear a nearby structure" bounty and no escort bounty.
- Patrol posts are not marked in the world; the page and the action bar give coordinates.
- Kills by a player's soldiers while the player is offline count for the quest line, not for slay bounties.
