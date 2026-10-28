# Village Expansion integration

Living Realms builds on **Villager Recruits: Village Expansion** (`village_recruits`, package `com.example.villagerecruits`, studied from `village_recruits-1.0.7.93TC-205.jar` with `javap`) when it is installed and `villageExpansion.enabled` is on. Without it, or with the switch off, Living Realms behaves as before: nothing below runs and no save data changes shape.

## How Village Expansion works

**Factions.** A village is a VE `VillageFaction` with the id `village_<x>_<y>_<z>`, backed by a Recruits faction (a scoreboard team plus a `RecruitsFaction` whose leader is VE's `FAKE_OWNER`) and a 7×7-chunk `RecruitsClaim`.
- Factions come from VE's own `tower_spawner` structure, which shares the vanilla village structure set, and from unowned recruits spawning near a Recruits recruit block. Vanilla villages are never converted.
- `VillageFaction.center` is the capital. `additionalCenters` are its other villages.
- The claim owner is authoritative. `VillageClaimIntegration.getAllCentersWithFactions` lists every village with its owner.
- Data lives in overworld SavedData (`villagerecruits_factions`, `village_recruits_cityplans`, `village_center_ownership`, …). Much of the runtime state is static and unsaved: war decisions, assault locks, old siege owners.

**Diplomacy.** VE uses Recruits' `RecruitsDiplomacyManager` (NEUTRAL, ALLY, ENEMY), written through `VRDiplomacy.setSymmetric`.
- `VillageAutoDiplomacy` decides relations from `FactionAssessment`s. Each assessment uses the faction's claim centres, strength and neighbours.
- Peace and alliance offers to player factions become consent prompts. War is applied directly.
- `PlayerClaimWarManager` declares war on nearby player claims at random: 35% within 600 blocks, otherwise 18%, plus a strength bonus, capped at 85%.

**Battles and capture.**
- `VillageAutoAttack` sends marching field armies at enemy villages. They need at least 15 troops and enough provisions.
- Inside the target claim, the field army becomes a Recruits claim siege. Before claim health can drop, the garrison must be cleared.
- A won siege posts `SiegeEvent.Success`. `VillageClaimConquestHandler` then moves the centre, garrison group, city plan and civilians to the winner, and splits a faction that lost its capital.
- `VillageFastConquest.conquerAbstract(level, centre, newOwner, oldOwner)` runs the same bookkeeping without a fight.
- Raids on plain player claims delete the claim instead of transferring it.

**Claims.**
- A village claim is a Recruits `RecruitsClaim`: a list of chunks, a centre chunk, an owner faction and a holder (`RecruitsPlayerInfo`, VE's `FAKE_OWNER` for villages).
- `createVillageClaimAt` lays out a square of 7×7 chunks (3 chunks each way from the centre chunk), writes it to Recruits' save data and indexes it with `RecruitsClaimManager.addOrUpdateClaim`, which posts a cancellable `ClaimEvent.Updated` and syncs it to every client. The world map draws it (Recruits' `ClaimRenderer`, VE's `VRClaimMapRenderer`).
- A claim never grows. A faction expands by founding new villages: `VillageExpansionManager` sends a settler party from an existing centre, and `completeExpansion` registers the new centre with its own 7×7 claim. `VillageExpansionData` holds the pending expansions.
- `PlayerClaimRules` splits claims in two. A village claim is owned by a VE faction or has a registered centre (`isAddonVillage`). Every other claim is a player claim:
  - `onClaimCreated` cancels a new player claim within 600 blocks of a village or pending tower, and VE founds no village within 600 blocks of one;
  - `PlayerClaimWarManager` declares war on nearby player claims and orders a field army at them;
  - `PlayerClaimRaidManager` runs that raid. It breaks beds while it lasts and, when it wins, `VillageClaimIntegration.removeClaim` deletes the claim.
- `VillageClaimIntegration.removeClaim` reloads Recruits' claim manager from its save data. A claim that is only in memory is lost.

**City planning.**
- `CityPlanGenerator.generate(level, id, centre, plotCount, seed)` is pure: it reads terrain and stores nothing. It returns a street tree along the founding cross with lots on both sides.
  - Streets have stations 16 apart, roads 3 wide and gaps of at least 17 between parallel roads, within a radius of 120.
  - Lots are zoned house, work, barracks, or production (lumber, mine, wool, farm, cow, forge).
  - Each plan uses one of four profiles: balanced, military, residential or industrial.
- VE's builder (`VillageAutoBuilder`) only builds for VE factions:
  - It pays from faction materials.
  - It places Workers-mod `BuildArea` templates (vanilla village houses, `workers_default`, `vr_production`).
  - It grows the plot count with a population cap of 30 to 120.

**Smaller systems.**
- npcgroups: one garrison group per centre, plus field armies.
- contracts: player reputation with factions.
- armory: per-faction troop loadouts.
- These systems affect a foreign faction only through relations and reputation.

## Design

**One owner per village.** With VE active, Living Realms stops converting generated villages. Each VE village appears as a *mirror* settlement (`Settlement.vxFaction`, see `settlement/Realms.java`).
- `Realms.sync` reconciles mirrors with VE's claims every 5 seconds.
- An old Living Realms NPC town standing on a VE village is adopted as that village's mirror instead of doubled.
- Mirrors are never simulated, built on or garrisoned by Living Realms, and they take no orders.
- Their soldier roster is the faction's own recruits in the village.
- Raids, conquest and the City Manager work on mirrors like on any rival city.

**Your realm.** Each player kingdom fights under a Recruits faction:
- the ruler's own faction if they have one;
- otherwise `lr_<uuid>`, led by the ruler and registered in VE with `VillageFactionManager.getOrCreate`.

Living Realms soldiers join the realm's team, which is how Recruits and VE targeting tell friend from foe. Raids between kingdoms therefore set ENEMY relations.

VE also filters every recruit's target change (`RecruitTargetStickiness`, `FactionTargeting.isEnemy`): a soldier with no faction, or one aiming at a non-enemy, keeps no target. Living Realms therefore registers its raid and alarm orders as group assignments (`noteGroupAssignment`). The Siege Overhaul raider hook in `compat` also marks the raiders' team as hostile to the realm.

**War.**
- VE factions declare war on nearby player kingdoms with VE's own odds for player claims (`Realms.considerWars`), because Living Realms cities hold no claims for VE's assessment to see.
- At war, villages march on your cities through Living Realms' raid system, using their own recruits.
- You declare war, make peace (VE's `aiWouldAccept` decides; a one-day truce follows) and raid villages from **Military → Realms**.
- Raids on or by villages require war. VE-on-VE wars stay entirely VE's.

**Conquest both ways.** Living Realms' rule stands: one capital, and losing it loses every city.
- `Kingdoms.transfer` moves VE records before Living Realms ones, ordinary villages first and the capital last, so VE never splits a faction that Living Realms annexes whole:
  - a claimed village goes through `conquerAbstract`;
  - a Living Realms city taken by a village faction becomes one of its claimed centres (`annex`), and its garrison surrenders to the faction.
- A Recruits siege that VE resolves (`SiegeEvent.Success`, heard at HIGH priority before VE's handler) applies the same rule.
- Sync repairs anything missed, for example after a restart.

**Town claims.** Each of your towns holds a Recruits claim (`settlement/TownClaims.java`, switch `townClaims.enabled`, on by default). It shows on the Recruits world map like a village's claim and grows like a village does, but on one claim.
- Placing the charter claims Village Expansion's 7×7-chunk founding square around the town's chunk. Every rank adds the chunks whose centre lies within the town's land (`Settlement.radius()` plus half a chunk), so the claim becomes round as the town grows. It never takes chunks held by another claim, and it is never placed over a centre chunk that is already claimed.
- The claim belongs to the realm's Recruits faction (`Realms.realm`). The ruler is its holder. The claim is named after the town, and its land stays open to building and use as before. The ruler may protect it in Recruits' claim screen.
- Living Realms checks the claim every second and keeps it in step with the town's land, name, owner and holder. It writes every change through to Recruits' save data, so Village Expansion's reload cannot lose it.
- Without Village Expansion, or with it switched off, the claim is a plain Recruits claim. The realm is the ruler's own Recruits faction, or `lr_<uuid>` led by them, the id that `VxBridge.realm` would use.
- Only overworld towns hold claims, because Recruits claims have no dimension. Villages you capture keep Village Expansion's own claim. Living Realms NPC kingdoms hold claims only for towns that they captured from you, and only with Village Expansion.

**What claims change, and how Living Realms keeps the upper hand.** To Village Expansion, a town claim is a player claim.
- *Founding near villages.* Living Realms keeps its claim when VE cancels it for being within 600 blocks of a village: the town is founded either way. VE's rule in the other direction still holds: no new village within 600 blocks of your towns.
- *Claim raids.* VE's field armies may raid a town's claim. Living Realms announces the raid and sounds the town's alarm, so the garrison turns out. The raiders fight and break beds as VE intends. If the raid wins, VE deletes the claim, but the town stays yours, and Living Realms lays the claim out again within a second and tells the ruler. A claim raid can harass a town, but it cannot take it.
- *Taking a town.* Only Living Realms rules take a town: a Living Realms raid, or a Recruits siege of the claim (`onSiegeWon`, the same capital rule). Unlike claim raids, both need the fight to happen in loaded land.
- *War.* VE's `PlayerClaimWarManager` now also weighs your towns and may declare war through its own odds as well as through `Realms.considerWars`. Its wars are silent; the first raid on a town announces itself.
- *Conquest.* `Kingdoms.transfer` moves the claims of every captured town (`TownClaims.follow`):
  - to the winning realm;
  - to the village faction as one of its centres (`VxBridge.annex`), held by `FAKE_OWNER` like its other claims;
  - if the winner has no Recruits faction (a Living Realms NPC kingdom without Village Expansion), the claim is dropped.
  - `Realms.before` sends only real Village Expansion villages (`VxBridge.village`) through `conquerAbstract`; a town's own claim never goes there.
- Without Village Expansion, a Recruits siege of a town claim cannot take the town. Living Realms puts the claim back under the town's realm.
- Switching `townClaims.enabled` off removes the town claims.

**City planning for your towns.**
- VE's planner cannot drive a player town directly. Its builder needs a VE faction with materials, Workers-mod templates and VE-owned claims. Registering your town as a VE centre would also hand it to VE's phantom-centre cleanup, auto-builder and garrison logic.
- So Living Realms calls the pure `CityPlanGenerator.generate` for your town and keeps the result as a `VxLayout`: lots with their zone and street, saved with the town.
- `Autonomy` grows the layout with the population, in VE's style.
- `AutoBuild` asks Living Realms' economy what category is needed, puts that building on a matching lot facing its street, then queues the street as an automatic road.
- Buildings are Living Realms blueprints paid from the stockpile. The City Map, Architect view and manual queue are unchanged, and your queued plans still pause automatic work.
- If no lot fits, the original site search runs.

## Trade-offs

- Your towns carry claims (see *Town claims*). VE's field armies can therefore raid them, and its sieges can reach them, but only Living Realms raids and sieges can take them. With `townClaims.enabled` off, your cities carry no claims and attacks on them come only through Living Realms raids.
- Cities you found and then lose to a village become VE centres without a tower. VE keeps them for an NPC faction. If you win them back, VE's phantom-centre cleanup may drop their VE record; the Living Realms city stays yours.
- New VE villages refuse to spawn within 600 blocks of a claim held by a player realm. With town claims on, that includes every town you found. That is VE's rule.
- Living Realms resets a town claim's chunks to the town's land every second. Chunks the ruler adds or removes by hand in Recruits' claim screen do not stay.
- Putting the ruler in a scoreboard team changes their name colour and turns off friendly fire with their own soldiers.

## Tests

`./gradlew runServer -PvillageExpansionTest -PvillageExpansionTestDir=<dir> -PvillageExpansion` covers:
- mirrors with no double conversion;
- realm registration;
- war declarations;
- a physical village raid on your city;
- capital loss and capture in both directions;
- a VE siege;
- consistency between Living Realms and VE;
- planning on VE lots;
- save and load;
- the switch;
- town claims:
  - created on founding, even near a village;
  - grown on tier-up;
  - kept through save and load and through a reload of Recruits' claims;
  - restored after a VE claim raid deletes them, with the town still yours;
  - moved on conquest, to a realm and to a village;
  - removed by `townClaims.enabled`.

Without `-PvillageExpansion`, the same test checks that nothing of the integration is active. It also checks plain Recruits town claims: founding, growth, save and load, conquest and the switch.

`./gradlew runClient -PtownRingTour -PrunDir=<dir> [-PvillageExpansion]` places a charter. It screenshots the town ring and the claim on the Recruits world map before and after a rank-up.

## If Village Expansion changes

Every VE call is in `settlement/vx/VxBridge.java`. The ones most likely to break:
- `CityPlanGenerator.generate`, `Plot` fields and template key names, used for lot zoning;
- `VillageFastConquest.conquerAbstract` and `VillageClaimConquestHandler.recordOldOwner`;
- `VillageClaimIntegration.getAllCentersWithFactions` / `createVillageClaimAt` / `transferCenterClaimOwner`;
- `VRDiplomacy.setSymmetric`;
- the `village_` id prefix and `FAKE_OWNER`;
- `VillageFaction` public fields (`center`, `additionalCenters`, `recruits`);
- for town claims: `PlayerClaimRules.isAddonVillage` / `isPlayerClaim`, `PlayerClaimRaidData.raids` (`claimId`, `attackerFaction`), the 600-block cancel in `onClaimCreated`, and `VillageClaimIntegration.removeClaim` reloading Recruits' claims from save data.
