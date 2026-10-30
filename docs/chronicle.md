# Town Chronicle

Every town keeps a history of its notable events, in short prose, dated by Minecraft day and chronicle year.

## What gets written

| Event | Example |
|---|---|
| Founding | "Testford was founded by Aldric, a handful of tents round a charter stone." |
| Rank-up | "By charter Testford became a Hamlet. The townsfolk feasted." |
| Notable buildings | The Town Hall, the first chapel, library, market, tavern, barracks, smithy, stables and watchtower; the Capital Castle; the town walls once every planned piece stands. |
| Raids | Held or lost (by attacker), won or failed (by target), on both towns' chronicles. A raid cut short by a change of rulers is left out. |
| Changes of rulers | Conquest, ceding, rebellion; becoming the kingdom capital. |
| Wars and peace | Living Realms wars and Village Expansion wars, in every city of both kingdoms. |
| Heroes | Soldiers promoted to Elite or Champion; the death of veterans (Veteran title or higher, 10+ kills, a legendary perk, Royal Guards, or merit 60+). |
| Fires | "The Great Fire of year 2, started by sparks from the forge, took the smithy and three cottages." (see [fires](fires.md)) |

Ordinary cottages, farms and population changes are not logged one by one. When nothing notable happens for 12 days, or before the next event after at least 4 quiet days, the stretch is folded into one line: "Days 12–30: Testford grew quietly to 14 buildings and 31 souls."

Each event type has two or three templates, chosen per town and entry, so chronicles do not read alike.

## Calendar and size

- Day N is the world's game day (game time / 24000 + 1), so `/time set` never rewinds history. A year is `chronicle.yearDays` days (default 30).
- A chronicle keeps `chronicle.maxEntries` entries (default 120); the oldest go first, the founding is always kept.
- Saved with the settlement (`chronicle` tag). Towns from older saves start their chronicle with their founding on the next tick and do not backfill.

## Reading it

- **City Manager → Chronicle**: fire risk card at the top, then the history, newest first or grouped by year.
- **Written copy**: the button in the Chronicle page gives you a vanilla written book (1 emerald from the stockpile, free in creative). Anyone can also use a blank **book** on the Town Hall to have the scribe copy it out. The book has a title page and the entries oldest first under year headings; long chronicles are cut at 100 pages.

## API for other features

```java
Chronicle.record(settlement, Chronicle.Kind.SIEGE, "Ashford", "the walls held");   // today
Chronicle.recordOn(settlement, day, kind, args...);                                 // a given day
Chronicle.kingdom(manager, kingdomId, kind, args...);                               // every city of a kingdom
```

The arguments of each `Kind` are documented on the enum (`{0}`, `{1}`... in its templates). `QUEST`, `TRADE`, `DISASTER`, `FIRE` and `NOTE` take one argument: the finished sentence.
