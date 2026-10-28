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

**City planning for your towns.**
- VE's planner cannot drive a player town directly. Its builder needs a VE faction with materials, Workers-mod templates and VE-owned claims. Registering your town as a VE centre would also hand it to VE's phantom-centre cleanup, auto-builder and garrison logic.
- So Living Realms calls the pure `CityPlanGenerator.generate` for your town and keeps the result as a `VxLayout`: lots with their zone and street, saved with the town.
- `Autonomy` grows the layout with the population, in VE's style.
- `AutoBuild` asks Living Realms' economy what category is needed and `VxPlan` ranks the free lots for it by Living Realms' districts (`plan/Zoning`), with VE's zone only as a tie-break. VE zones lots by emission order, so its lumber, mine, farm and forge lots are among the most central; Living Realms keeps those trades out of the centre instead.
- The building's door is turned to the lot's street (`Blueprint.front`) and its front wall placed just off the road, centred on the lot. VE's `Plot.anchor` is the lot centre (its overlap test is symmetric about it).
- The street is queued as an automatic road: the part inside the town's land, paved like a high street when VE marks it `primary`.
- Living Realms' own street plan is not used while a VE layout exists, so the two never cross. Buildings that find no VE lot take an open site in their district, door toward the centre, with a lane to the nearest road.
- Buildings are Living Realms blueprints paid from the stockpile. The City Map, Architect view and manual queue are unchanged, and your queued plans still pause automatic work.
- If no lot fits, the original site search runs.

## Trade-offs

- Your own cities carry no VE claims. VE's native field armies and sieges therefore only reach villages you captured from VE. Attacks on your other cities come through Living Realms raids, which need loaded chunks like any Living Realms raid.
- Cities you found and then lose to a village become VE centres without a tower. VE keeps them for an NPC faction. If you win them back, VE's phantom-centre cleanup may drop their VE record; the Living Realms city stays yours.
- New VE villages refuse to spawn within 600 blocks of a claim held by a player realm. That is VE's rule.
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
- the switch.

Without `-PvillageExpansion`, the same test checks that nothing of the integration is active.

## If Village Expansion changes

Every VE call is in `settlement/vx/VxBridge.java`. The ones most likely to break:
- `CityPlanGenerator.generate`, `Plot` fields and template key names, used for lot zoning;
- `VillageFastConquest.conquerAbstract` and `VillageClaimConquestHandler.recordOldOwner`;
- `VillageClaimIntegration.getAllCentersWithFactions` / `createVillageClaimAt` / `transferCenterClaimOwner`;
- `VRDiplomacy.setSymmetric`;
- the `village_` id prefix and `FAKE_OWNER`;
- `VillageFaction` public fields (`center`, `additionalCenters`, `recruits`).
