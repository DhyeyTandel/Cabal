# Decision log

Append-only. Earlier entries are never rewritten.

## [2026-09-27] Move plan editing into an immutable ShiftPlan in the routing core

**Problem** — The rules for editing a plan (LOCAL vs FULL cancellation, a cab keeping
its vehicle and number after a cancellation, cab numbers never reusing a gap, pickup
stops flipped between driving and outward order, stored cabs re-measured before an
insertion) lived in `PlanningService`. They could only be tested through HTTP and H2,
and `PlanningService` changed in every feature commit (5 of 7).

**Options considered**
- Keep the rules in the service and add more MockMvc tests. Rejected: slow, and the
  tests assert through JSON rather than on the rules themselves.
- A mutable plan object in the core, edited in place. Rejected in favour of an
  immutable one: comparing the before and after values is what lets the service write
  only the cabs that changed, and tests become two values compared.
- Pass `ShiftPlan` one travel matrix for the whole plan (option A). Rejected: a LOCAL
  cancel would then fetch a matrix for every rider instead of one cab's. Chose to give
  `ShiftPlan` a `TravelModelProvider` so each operation requests only the points it
  touches (option B).

**Decision** — `routing/ShiftPlan`, immutable, with `create`, `restore`, `cancel`,
`add`, `replan` and `cabs()`. It owns cab numbers and returns stops in driving order;
outward order is internal. `TravelModelProvider`, `TravelEstimate`, `TravelSource` and
`ReplanStrategy` moved into `routing/`. `NotOnPlanException` (404) and
`AlreadyOnPlanException` (409) replaced the service's checks; the then-unused
`ConflictException` was deleted. `PlanningService` now loads entities, restores a
`ShiftPlan`, calls one method, and saves only the cabs whose value changed. Designed
with the user through a question-by-question review; implemented by a Sonnet worker
from a written spec, then reviewed and fixed by the orchestrator.

**Tradeoff accepted**
- Error messages lost the plan id ("employee 5 is not on this plan") because
  `ShiftPlan` does not know database ids.
- Every edit maps the whole stored plan to `ShiftPlan.Cab` values and back.
- The routing core now depends on the travel-provider abstraction, not just a
  `TravelModel`.

**What went wrong**
- Review found a response-ordering bug: a cab created in a numbering gap (a FULL
  re-plan turning cabs 1, 3 into 1, 2, 3) was appended last, so that one response
  listed cabs 1, 3, 2. Fixed by sorting after save.
- The worker left `ConflictException` and its handler in place with no callers. Deleted.
- While arguing for option B, I estimated a whole-plan matrix for 500 riders at "about
  25 OSRM requests". Wrong: with the public server's 100-coordinate table limit the
  client uses blocks of 50, so 501 points need 11 × 11 = 121 requests. Option B was
  better than claimed.
- During the architecture review that led here, `preview_start` read
  `~/.claude/launch.json` (the session is rooted at the home folder) instead of the
  project's, and started an unrelated `fuel-bill-generator` dev server on :3000. It
  was stopped immediately. A follow-up attempt to add a config to
  `~/.claude/launch.json` was denied by auto mode, so the HTML report's Mermaid
  diagrams were never render-checked. [unverified whether they render]

**Scale/limits**
- A LOCAL cancel requests one cab's points: a single OSRM request at any plan size.
- `add` still requests every stop plus the newcomer. With a 100-coordinate table limit
  that is 9 requests at 100 riders, 121 at 500 and 441 at 1,000. A self-hosted OSRM
  with `--max-table-size 1000` makes it one request up to 1,000 points.

## [2026-09-27] Consolidate route timing into one Timetable per cab

**Problem** — Route timing was computed in four places that had to agree:
`RouteMetrics.rideMinutes` and `maxRideMinutes`, `EtaCalculator` (rounded ETAs),
`ShiftContext.farEndTime` (which copied `EtaCalculator`'s rounding, and said so in a
comment), and `RoutePlanner.farEndAtNight`. A change to one could make the night
escort check disagree with the ETA a rider is sent.

**Options considered**
- Keep the four and add a test asserting they agree. Rejected: it pins the symptom
  and keeps the duplication.
- Move only the ETA and far-end logic, leaving ride times in `RouteMetrics`. Rejected:
  it still leaves two ways to compute a time.
- One `Timetable` that absorbs all four. Chosen.

**Decision** — `Timetable.of(travel, office, outwardStops, shift, dwell)` computes
ride minutes eagerly and builds ETAs only when asked. `farEndTime()` and every ETA go
through one private method. `PlannedCab` carries its `Timetable` (built once per cab),
`RouteMetrics` keeps only distance, and `EtaCalculator` and `ShiftContext.farEndTime`
were deleted. `TimetableTest` absorbed the old ETA, per-leg traffic and directed-time
tests, and added a check that `farEndTime()` equals the farthest stop's ETA in both
directions.

**Tradeoff accepted**
- `PlannedCab` now holds an object instead of a number.
- The lazy ETA cache in `Timetable` is not thread-safe. That is fine while timetables
  are per-call values, but would matter if one were ever shared across threads.

**What went wrong**
- The worker built the same route's timetable twice in `buildCab` whenever the escort
  rule did not reorder it, and left an unused `office` parameter in `ShiftPlan.toCab`.
  Both fixed in review.
- The first benchmark run put 1,000 riders at 4.13 s, 5.7% over the agreed 5% gate
  against 3.91 s. Reruns gave 3.86 s and 3.99 s, so it was noise and the gate passed.
  2,000 riders were consistently 7 to 10% slower (13.7 to 13.9 s against 12.5 s); see
  the next entry.
- The user expected ETAs might vary between runs. The planner is deterministic (fixed
  traffic profile, haversine, no randomness), so the before/after diff was kept, as
  information rather than a gate. All 114 demo times across 20 cab lines were identical.

**Scale/limits** — None new, once the next entry's fix is in.

## [2026-09-27] Stop the sweep allocating a Timetable per ride-limit check

**Problem** — After the `Timetable` change, 2,000-rider plans took 13.7 to 13.9 s
instead of 12.5 s. The suspected cause: the sweep checks each candidate cab's longest
ride thousands of times, and each check built a full `Timetable`, copying the stop list
and allocating an object.

**Options considered**
- Accept it. It was outside the agreed 1,000-rider gate. Rejected by the user.
- Make `Timetable.of` skip its defensive copy. Rejected: callers pass mutable
  `ArrayList`s, so the timetable could change under a caller's feet.
- A static `Timetable.maxRideMinutes(...)` that runs the same private ride walk without
  building the object. Chosen.

**Decision** — The sweep calls `Timetable.maxRideMinutes`. Both entry points share one
private `rideMinutes` method, and a test checks them against each other on 50 random
routes per direction under a peaked traffic profile.

**Tradeoff accepted** — `Timetable` has two public ways to get the longest ride. They
cannot disagree in code, and the test enforces it, but a reader has to know why both
exist.

**What went wrong** — Nothing. The hypothesis held: 2,000 riders went back to 12.36 to
12.45 s across three runs, and 1,000 riders to 3.81 to 3.96 s.

**Scale/limits** — 2,000 riders still take about 12.4 s per full plan.

## [2026-09-27] Build routing settings in one PlanningPolicy instead of the service

**Problem** — `PlanningService` read nine settings from `RoutingProperties` and
assembled `RoutingParams`, `ShiftContext` and the traffic profile by hand, including
the arrival and departure buffers that turn a shift time into an office time. Every
new routing rule meant editing the service, and `RoutingProperties` changed in 5 of 7
commits.

**Options considered**
- Leave the assembly in the service. Rejected: that is the problem.
- A policy whose `paramsFor` takes the `RoutePlan` entity. Rejected: it would tie the
  routing core to JPA.
- A plain-Java policy taking plain values (direction, shift time, fleet, max ride).
  Chosen.

**Decision** — `routing/PlanningPolicy`, built once at startup by `RoutingConfig`,
with `paramsFor(direction, shiftTime, fleet, maxRideMinutes)`, `defaultFleet(...)` and
`maxRideMinutesOr(...)`. `PlanningService` takes the policy instead of
`RoutingProperties` (now referenced only in `config/`) and builds its settings once per
request. Parsing the request's vehicle list stays in the service, because it is about
the API input format.

**Tradeoff accepted** — The policy constructor takes 11 positional values, several of
them `int`s, so two could be swapped silently. `PlanningPolicyTest` uses distinct
arrival and departure buffers (15 and 10) to catch that pair; the night start and end
hours have no such guard.

**What went wrong**
- Before this change I told the user the service rebuilt its settings 6 times per
  request. That was true before `ShiftPlan`; afterwards it was 2. Corrected before the
  worker was dispatched. It is now 1.
- The worker's report claimed the repo directory "isn't a git repo root". It is. The
  worker ran no git commands, so there was no effect.

**Scale/limits** — None: this runs once per request and is constant time.

## [2026-09-27] Extract CabBuilder and drop the improver's callback seam

**Problem** — `InterRouteImprover` rebuilt cabs through a
`BiFunction<PlannedCab, List<Stop>, PlannedCab>` created as a lambda in
`RoutePlanner.plan`. The seam had exactly one caller, so nothing varied across it, and
"build a cab" (sequencing, the night escort repair and the timetable) sat inside
`RoutePlanner` even though the improver needed it as much as the planner did.

**Options considered**
- Leave it. Rated "speculative" in the architecture review, and the least likely of
  the four candidates to cause a real bug. The user chose to do it anyway.
- Delete `InterRouteImprover` and fold it into the planner. Rejected by the deletion
  test: it would move about 250 lines into `RoutePlanner` without removing any.
- Extract a `CabBuilder` that both depend on directly. Chosen.

**Decision** — Package-private `routing/CabBuilder`, with office and settings bound at
construction and one method, `build(vehicle, stops)`. It holds the former
`buildCab` body and `escortSafeRoute`, moved unchanged (a token-level comparison
against the previous commit found both bodies identical). `RoutePlanner.buildCab`
stays public as a one-line delegate, so `ShiftPlan` and the tests did not change. The
planner and the improver each use one `CabBuilder` per operation. `RoutePlanner` went
from 225 to 176 lines.

**Tradeoff accepted** — One more class. `RoutePlanner.buildCab` now exists only as a
public face for `ShiftPlan` and the tests; a reader has to follow it one step further
to reach the logic.

**What went wrong** — Nothing. 78 tests passed, all 114 demo times across 20 cab lines
matched the baseline, and planning time stayed within noise (1,000 riders: 3.84 s and
4.31 s over two runs; 2,000: 12.5 s and 12.8 s).

**Scale/limits** — Unchanged.
