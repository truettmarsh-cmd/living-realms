# Citizens

The population of a Living Realms town is made of people. Code is in `livingrealms.citizen`; the page is
`client/ui/PeoplePanel.java`.

## Who they are

Every head of population has a record (`Citizen`): a given and family name, sex, age, job, home, spouse, parents,
one or two traits and a mood. Children are records too but are not counted in the population.

- **Names** follow the town's building style: English given names and trade surnames in the plains, Persian and Arabic
  names in the desert, Iberian and Italian names in the savanna, and Norse given names with patronymics (Bjornsson,
  Astridsdottir) in the taiga and snow. A child takes a parent's family name.
- **Age.** A town day is half a year (`People.YEARS_PER_DAY`). Children come of age at 16 and the elderly start at 60.
- **Job.** The simulation's worker counts are handed out to adults. People keep their job while it exists; the young
  take new work first, and pious people go to the chapel first.
- **Home.** Households (a couple or a single person, with their children) live in a house, the Town Hall's beds, a
  tavern room or one of the houses the village came with, counted as the simulation counts housing. They stay while the
  building stands. If the town is overcrowded, some have no home.
- **Traits** (1–2 each, a child often shares one with a parent):

| Trait | Personal effect | As mayor |
|---|---|---|
| Hardworking | +3 mood at work, −8 idle | builders 8% faster |
| Pious | +5 with a chapel, −3 without | +3 town mood |
| Brave | half the fear after raids and fires | the whole town feels half |
| Greedy | taxes count twice in their mood | +15% taxes, −2 town mood |
| Sickly | −4 mood, old age from 54 instead of 64 | none |
| Cheerful | +6 mood | +4 town mood |

Hardworking builders also speed construction by 2% each (up to +10%).

- **Mood** starts from the town's mood, then: +4 for their own house, −10 for no home, work (see above), +3 married,
  traits, −15 grieving for 5 days after a spouse, parent or child dies, +12 for 5 days after a favour granted, and fear.
  The People page lists these reasons for each person.

## Visible settlers

The townsfolk walking about are citizens: each spawned settler takes someone with its job who is not already out.
The name tag shows only when you look at them (vanilla behaviour for named mobs). Right-clicking one shows their age,
job, traits and mood. A settler whose citizen died or left disappears. Settlers from before a town had citizens are
replaced. When a settler is killed, that person dies, and the cause ("in a fire", "at the hands of Zombie") is
recorded.

## Life

All of this runs in the settlement's simulation step, so unloaded towns go on living on their records. Whole days are
handled once each, with at most 4 per step.

- **Population.** There is one adult record per head of population, up to 200 (`People.MAX_ADULTS`). When the
  population changes for any reason (training soldiers, raids, other features), people arrive or leave to match. The
  people who leave are single, young and never the mayor, and their young children leave with them. Bigger towns show
  "plus N more not on the rolls".
- **Growth.** There is no second growth system. When the town's ordinary growth adds a person, the eldest youth who
  has come of age (16+) takes the place. If there is no such youth, a newcomer arrives from the road. The log names
  them. Each youth waiting adds +0.5 a day to growth (up to +1.5). A youth who finds no room by 24 leaves town.
- **Weddings.** At most one a day (35% chance) between two unmarried adults aged 18–55 who are within 15 years of each
  other and are not siblings or parent and child.
- **Births.** At most one a day. Each couple whose partners are both 46 or younger and who have fewer than 4 children
  has a 15% chance. This needs a fed town (not starving, 2 food per head in the stockpile), mood 45+, a free bed
  counting children, and fewer children than a third of the adults + 1 (at most 40).
- **Death.** From 64 (54 if sickly), the daily chance is (age − 63) / 50, up to 60%. At 95 it is certain. At most two
  die a day. A death lowers the population, is kept in the memorial (the last 12) and makes the family grieve.

## Fear

When the chronicle records a raid (held 12, lost 25), a siege (18), a fire (20), a disaster (15), a conquest (10), an
uprising (8), fallen soldiers (6) or a war (5), the town is frightened: every citizen loses that much mood (half if
brave), and the town's mood target loses half of it (a quarter with a brave mayor). The fear fades by 5 a day. The
largest fear wins. Other features can call `Citizens.shock(settlement, amount, why)`.

## Mayor and captain

- **Mayor.** Respect is age (up to 50) × 0.4, +3 for elders under 68, −6 for older elders, the standing of their work
  (priest and scholar highest, idle −10), traits, +5 married, +3 per child (up to 9) and mood / 10. The most respected
  adult is elected when the office is empty and every 12 days. A sitting mayor is only replaced by someone 5 or more
  points ahead.
- **Captain.** The best soldier by rank × 10 + kills × 2 + merit / 2 + level (+15 for a Royal Guard). The captain is
  chosen when the post is empty, and after 10 days may be replaced by a soldier scoring at least 1.5 × theirs + 10.
- **Appointing.** In City Manager → People the ruler can appoint any adult as mayor, or any soldier as captain. An
  appointed mayor stays until death, or until you press **Hold vote**. An appointed captain stays until death, or until
  you press **By merit**.
- **Loyalty.** In a conquered city, if the average mood is under 30 and the mayor is greedy or miserable, loyalty
  falls by 3 a day ("a hated mayor"). A mayor who is not greedy, in a city with mood 65+, adds 1 a day.

## Chronicle

New kind `PEOPLE`. Only notables are written down: the first mayor, elections that change the mayor, appointments,
the mayor's wedding and death, and each new captain. Births, ordinary weddings and deaths go only to the town log
(player towns). Over 120 simulated days the self-test town wrote 6 such entries against more than 100 births and
deaths.

## Requests ("a citizen asks…")

In player towns with 4+ people, each day there is a 20% chance that someone asks for something, if nobody already has:

- the parent of a sick child: 1 golden apple
- the smith: 16 iron
- a farmer: 24 bone meal
- the scholar: 6 books
- the priest: 8 candles
- the innkeeper: 4 honey bottles

The request goes on the town's bounty board the next time the board has room, as a normal **Deliver** bounty with the
reason "Edith Miller asks: …". It is posted once and pays 2 + rank emeralds (+2 for the apple). When it is completed,
the person is glad (+12 mood for 5 days). An unposted request lapses after 3 days.

## City Manager → People

- **Header:** townsfolk, children, average mood, and births, deaths and weddings so far.
- **Notables:** the mayor (elected or appointed, bonus, days to the next vote) and the captain. Each has an
  **Appoint** button, which turns the list into a list of candidates, each with a **Choose** button.
- **Banners:** an open request, and the town's fear.
- **List:** searchable by name, job, trait or stage, scrollable. Each row shows the name, job, age, traits and mood.
- **Card:** for the person selected: age, job, home, respect, a mood bar with its reasons, traits with their effects,
  and family. Click a relative's name to go to them. With nobody selected, the card shows the memorial.

## Saving

`people` tag in the settlement. A missing tag loads empty, and the town gets its citizens on its next simulation step
(with a few families and a first mayor). The chronicle's earlier entries are not read for fear.

## Test

`./gradlew runServer -PcitizenTest -PcitizenTestDir=<fresh dir>` writes `citizen-test-result.txt` (184 assertions):

- identities, families, jobs matching the worker counts, and homes
- save and load round trip, and lazy creation for old saves
- arrivals and departures following the population
- weddings (and a mayor's wedding in the chronicle), births and none while starving, coming of age through growth,
  and newcomers
- death of old age with mourning, the mayor's death and a new election, and death in a fire
- fear after a fire and after a raid (brave citizens half), fading
- elections, incumbency, appointment, and holding a vote
- mayor bonuses and loyalty
- captain choice and appointment
- a request posted on the bounty board and granted
- 120 days on records only (bounded, little chronicle), a 500-person town capped at 200 records (about 0.1 ms a step)
- a real settler named after a citizen, killed in a fire
- a real simulation step naming a newcomer

## Not done

- Children and youths are records only. No baby settlers walk about.
- Traits do not change anything about how settlers behave in the world. A brave settler still flees monsters.
- Soldiers are not citizens. The captain is a soldier record. Recruiting lowers the population, which removes a
  citizen; it does not turn that person into the soldier.
- Fear is read from the chronicle, so a raid shows only when it ends.
