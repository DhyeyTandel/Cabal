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

## [2026-09-28] Make Cabal deployable on a small server behind a Cloudflare Tunnel

**Problem** — The user wants Cabal live as a website on a small Linux server [redacted:
server details] that also runs other services, without it being heavy. The app had
no web page, no protection for writes, no production settings and no deployment path,
and anyone reaching a public copy could have created 2,000-rider plans at about 12 s
of CPU each.

**Options considered**
- Docker Compose with a reverse proxy. Rejected: the Docker daemon's own overhead, and
  Docker is not installed on the Mac, so the setup could not be tested here. Plain
  systemd plus an apt PostgreSQL could be syntax-checked locally and matched the
  server's existing systemd use.
- Opening an inbound port. Rejected: it may not be possible behind carrier-grade NAT,
  and it would expose the server's IP address. The user already runs a
  Cloudflare Tunnel, so a hostname ingress rule was chosen.
- Self-hosting OSRM, now that RAM is plentiful. Deferred: several GB and extra moving
  parts before the demo needs real road times.
- JVM setups, all measured on the Mac with the demo plus a 500-rider plan: default
  (362 MB and growing, 0.5 s), 192 MB heap (289 MB, 0.7 s), lean 128 MB heap (220 MB,
  0.6 s), lean with the C1 compiler only (171 MB, 0.7 s). The user chose a 256 MB heap
  with the full JIT, as a margin for large plans at a still-small footprint.

**Decision**
- `deploy/`: a hardened `cabal.service` (256 MB heap, serial GC, `MemoryMax=512M`,
  `CPUWeight=50`, systemd sandboxing), an idempotent `setup-server.sh`, an
  `install-release.sh` that health-checks and rolls back, a Mac-side `deploy.sh`, and
  the single tunnel rule for `cabal.dhyeytandel.in`.
- A `prod` profile: bind to 127.0.0.1, small Tomcat and Hikari pools, graceful
  shutdown, compression, a 300-rider cap, and a required API key.
- An API key filter on every non-read request, `/healthz`, `GET /api/plans`, and a
  read-only Leaflet map page in the owner's design system.
- Other services on the machine are never routed through the tunnel, and setup never
  touches them.
- The app side was built by a Sonnet worker from a spec; the server side was written
  by the orchestrator because it runs as root on the user's machine.

**Tradeoff accepted**
- The site is only up while the server is on and online.
- Deploys need the server's sudo password, so the user runs them; the assistant cannot.
- Static files revalidate on every page load (a 304 when unchanged) instead of being
  cached, trading a round trip for never serving stale JavaScript after a deploy.
- The map depends on OpenStreetMap's public tile servers, which are fine for light
  demo traffic under their usage policy but not for heavy use.

**What went wrong**
- The worker's filter checked `request.getRequestURI().startsWith("/api/")`. Against
  the real Tomcat, `POST /%61pi/offices` with the key returned 201, which proves that
  path routes to `/api/offices`; the raw URI does not start with `/api/`, so without a
  key the old filter would have let that write through. Fixed by requiring the key for
  every non-read method on any path. Afterwards all six path variants tried without a
  key returned 401, and a regression test was added. MockMvc could not have caught this.
- The spec told the worker to use CARTO Positron tiles. They now require an API key,
  and the page showed "API KEY REQUIRED" tiles. Switched to OpenStreetMap's own tiles
  with a CSS filter to match the palette.
- The selected plan pill was blank in dark mode: its text used `--on-ink`, which does
  not flip with the theme while its background (`--ink`) does. Fixed with `--paper`.
- The prod profile cached static files for an hour, which would have served old
  JavaScript after each deploy. Switched to revalidation.
- During testing, a loop variable named `path` wiped the zsh command path (in zsh,
  `path` is tied to `PATH`), so the first bypass run printed only
  "command not found: curl". The run was repeated with a different name.
- A stale health check hit the previous app while it was still draining under graceful
  shutdown, so it briefly looked as if the new cache settings had not applied.
- Earlier, while inspecting the report for the architecture review, `preview_start`
  launched an unrelated project's server (recorded in the ShiftPlan entry).

**Scale/limits**
- About 250 to 300 MB for the app plus PostgreSQL's own memory, capped at 512 MB by
  systemd.
- 300 riders per plan in production. A 300-rider plan takes well under a second.
- Tomcat serves 20 concurrent requests with 50 queued; Cloudflare absorbs the rest.

## [2026-09-28] Suggest and apply dissolving a cab into its neighbours

**Problem** — LOCAL cancellations keep every other driver's route stable, but over a
shift they leave cabs half-empty. The only way back was a FULL replan, which moves
every driver. Dispatchers need a middle path that touches only a few cabs.

**Options considered**
- Apply consolidation automatically after cancellations. Rejected: once drivers are
  notified, a person should decide whether a saving is worth changing routes.
- Pair merges (two cabs become one vehicle). Offered; the user chose dissolving only.
- Dissolving one cab into its neighbours' free seats. Chosen.
- Storing suggestions and applying them by id. Rejected: suggestions go stale as soon
  as anyone cancels or books, so applying takes a cab number and recomputes.

**Decision** — `ShiftPlan.dissolveSuggestions()` and `ShiftPlan.dissolveCab(n)`. For a
cab, its riders move farthest-first by cheapest insertion into its six nearest cabs
(free seat or upgrade, never a new cab), rejected if any rider has nowhere to go, a
receiver would newly need a guard, or the total cost would not fall. Endpoints:
`GET /api/plans/{id}/dissolve-suggestions` and `POST /api/plans/{id}/cabs/{n}/dissolve`
(409 when no longer valid, 404 for an unknown cab). `RoutePlanner` gained
`bestInsertionIntoExisting`, sharing its scoring with `bestInsertion`, and the
centroid-neighbour logic moved into a shared `CabNeighbours`. Built by a Sonnet worker
from a spec; the fleet-accounting fix below was made in review.

**Tradeoff accepted**
- Suggestions are computed independently, so they conflict with each other; the
  dispatcher applies one and re-fetches.
- Only one cab per operation and only into six neighbours, so it recovers less than a
  FULL replan could.
- Farthest-first cheapest insertion is greedy; a different rider order could sometimes
  save more.

**What went wrong**
- The worker's version passed only the six neighbour cabs to the insertion call, which
  derives free vehicles from the cabs it is given. A vehicle driven by any cab outside
  the neighbourhood therefore looked free and could be offered as an upgrade, putting
  one vehicle on two cabs. None of the worker's tests had busy vehicles outside the
  neighbourhood. A regression test (the fleet's only SUV on a far-away cab, six full
  sedans around the cab being dissolved) failed on the worker's code; the fix computes
  free vehicles from every cab except the dissolved one, updated after each insertion.
- The worker had to raise the API test's ride limit to 240 minutes before any cab was
  dissolvable, which looked like the feature might rarely apply. Measured on the demo
  at the default 90 minutes: no suggestions while cabs were full; after four
  cancellations, four suggestions, the best saving 857 of 6,794. The 240 was an
  artefact of that test's fixture.
- The worker's upgrade-path test combines two separately planned clusters by hand,
  because the planner never leaves a scarce big vehicle split that way on its own.
- During the live check, `[ a == b ]` failed under zsh ("= not found"); rechecked with
  jq.

**Scale/limits** — Per call: one travel request for the whole plan, then for each cab
up to (riders × 6) cab rebuilds. At the 300-rider production cap (about 75 cabs) that
is at most a few thousand small rebuilds.

## [2026-09-28] Add employee time windows (earliest pickup, latest drop)

**Problem** — Employees had no way to say "do not pick me up before 06:00" or "get me
home by 23:15". The demo's 07:30 pickup had people collected at 05:43.

**Options considered**
- Per-shift windows on a booking. Rejected: the model has no bookings, and adding them
  was out of scope for a same-day deploy.
- Soft windows (a cost penalty). Rejected in favour of hard constraints, matching how
  the ride limit already works.
- A general time-window VRP with waiting at stops. Not needed: pickups are timed
  backwards from arrival and drops forwards from departure, so both windows reduce to
  a per-rider ride cap and fit the existing checks.

**Decision** — Optional `earliestPickup` and `latestDrop` on the employee (V5), read
live when a plan is built or edited; `PUT /api/employees/{id}/time-window` to change
them. `Timetable` checks windows with the same rounded minute offset as the published
ETAs, resolving windows across midnight, and only does date maths for stops that have
a window. Enforced in the sweep (`Timetable.fits`), insertion, the inter-route search,
the escort reorder and (through insertion) dissolving. A lone rider always gets a cab
and is flagged `windowMissed` if even that misses. The response carries each stop's
window and a plan-level `windowsMissed`; the page shows "after 06:00" / "by 23:15" and
marks misses. Built by a Sonnet worker; verified by the orchestrator.

**Tradeoff accepted**
- Windows cost vehicles. On the demo 07:30 pickup: 5 cabs and 7,163 became 7 cabs and
  8,317 (+16%) for three windows, one of which cannot be met anyway.
- Preferences are standing, not per shift, and a change reaches an issued plan only at
  its next edit.

**What went wrong**
- The saved demo baseline had been cleared from the scratch folder, so the
  "no windows means no change" check had to rebuild a baseline by running the previous
  commit in a separate clone on another port. Result: all 114 times identical.
- The worker added `@JsonFormat(pattern = "HH:mm")` to every `LocalTime` field; without
  it Jackson writes "06:00:00", contradicting the API contract.
- Benchmark after the change: 1,000 riders 4.03 s (inside the 3.8 to 4.3 s spread seen
  before); 2,000 riders 13.2 s against 12.4 to 12.8 s in recent runs, about 4% higher,
  one run only. [unverified whether that is noise or the extra check]

**Scale/limits** — No extra work for riders without a window. A window that even a
solo cab cannot meet is reported, not solved.

## [2026-09-28] Let visitors try the planner on the public site

**Problem** — The deployed page only showed two pre-computed plans, so to a recruiter
it could have been a static site. The user wanted visitors to be able to run the
engine themselves.

**Options considered**
- Keep it read-only. Rejected by the user: it does not show that the engine is live.
- Let visitors create real plans with a public key or none. Rejected: every stored
  write would become public and abusable.
- A public, stateless sandbox endpoint with tight limits. Chosen.

**Decision** — `POST /api/sandbox/plan` runs `ShiftPlan.create` in memory for a fixed
office and demo date and returns the plan; nothing is persisted. The API key filter
exempts exactly that raw URI and no other spelling (it fails closed). Limits: 40
riders within 25 km, 10 plans per minute per client (`CF-Connecting-IP`, trustworthy
because the app only listens on 127.0.0.1 behind the tunnel), 2 at once, 429 with
`Retry-After` beyond. The page gains a "Try it" tab (drop pins or add 20 random, pick
shift and fleet, plan) that reuses the demo renderer. Built by a Sonnet worker; the
body-size filter below was added in review.

**Tradeoff accepted**
- Anyone can make the server compute small plans; the rate and concurrency limits and
  the 40-rider cap bound the cost.
- Rate-limit state is in memory, so it resets on restart and is per instance.
- Pins placed by hand are never marked as women, so the escort rule only shows up with
  random riders.

**What went wrong**
- Review found that Spring parses the whole JSON body before the controller, so the
  40-rider cap and the rate limit only applied after parsing, and no body size was
  capped. One large request could have exhausted the 256 MB heap and put the service
  in a restart loop. Added `SandboxRequestLimitFilter`: over 32 KB is refused with 413
  and an undeclared length with 411, before any parsing. Verified live: a 102 KB body
  got 413 and a chunked one 411.
- Verified live in the prod profile: sandbox without a key 200 with database row counts
  unchanged; `/api/sandbox/plan/`, `//api/sandbox/plan`, `/api/sandbox/%70lan`,
  `/api/sandbox/plan/../../offices` and `/api/offices` all 401 without a key; 10
  requests then 429 with `Retry-After: 30` for one client while another was served.

**Scale/limits** — At most 2 concurrent sandbox plans of at most 40 riders each, each
taking milliseconds.

## [2026-09-28] Redaction of home-setup details (one-time exception to append-only)

**Problem** — The repository is public so recruiters can read the code, and the
deployment entry above described the owner's home server hardware, the other services
on it and their home network.

**Options considered**
- Leave earlier entries untouched, as this log's append-only rule requires.
- Redact those details in place and record the exception here. Chosen by the user.

**Decision** — Edited the entry "Make Cabal deployable on a small server behind a
Cloudflare Tunnel" in place: its title, the server description in its problem
statement, the inbound-port option, the rule about other services and the uptime note.
Nothing else in any entry changed, and no reasoning was removed, only identifying
details.

**Tradeoff accepted** — This log is no longer strictly append-only. The original
wording remains in the git history, which was not rewritten.

**What went wrong** — nothing.

**Scale/limits** — n/a.

## [2026-09-28] Fix stress-test and live-site findings

**Problem** — A stress test and the first live check found: over-long names gave 500;
the sandbox refused nearly everyone during a burst (3 of 400 served); no browser
security headers; `/error` requested directly gave 500 with status 999; on the live
page the "not saved" label showed on the Demo plans tab and the map opened at zoom 19;
and the tunnel instructions assumed a locally configured tunnel when the real one is
dashboard-managed.

**Options considered**
- For the sandbox burst: raise the concurrency limit alone (more CPU per burst, same
  instant rejections), or let requests wait briefly for a slot. Chose 3 at once plus a
  2-second wait for at most 10 waiters.
- For headers: rely on Cloudflare settings, or set them in the app. Chose the app, so
  they apply whatever sits in front, plus HSTS in Cloudflare.

**Decision** — `@Size(max = 120)` on names; `SandboxGuard` with 3 concurrent, 2 s wait,
10 waiters; a `SecurityHeadersFilter` (CSP limited to self, the Leaflet CDN, Google
Fonts and OpenStreetMap tiles, plus nosniff, frame denial, referrer and permissions
policies) ordered first so rejections carry them too; an `ApiErrorController` that
never exposes exception details; the label and map fixes (`invalidateSize` then
`fitBounds` with maxZoom 13 once the container is visible); `deploy/cloudflare-tunnel.md`
for the dashboard-managed tunnel. Built by a Sonnet worker, reviewed and adjusted by
the orchestrator.

**Tradeoff accepted**
- Bursts are still mostly refused: 50 simultaneous plans get 13 served (3 computing +
  10 waiting) and 37 "busy"; 400 over a short burst got 14. Admitting more would tie
  up Tomcat's 20 request threads.
- The CSP forbids inline scripts and styles, so future page changes must stay in
  app.js and app.css.

**What went wrong**
- The worker set the waiter cap to 20, equal to Tomcat's 20 request threads, so a
  flood of waiting sandbox requests could have held every thread for 2 s and stalled
  ordinary page loads. Lowered to 10 in review.
- The map bug did not show on localhost earlier, only over the real network: the fit
  ran while the container still had no size, which depends on timing.

**Scale/limits** — Unchanged from the sandbox entry, except 3 concurrent plans and at
most 10 waiting.

## [2026-09-28] Fix the live page's map-scroll trap and washed-out tiles

**Problem** — The user reported the live UI as "very bad". Inspecting the deployed
page (desktop, mobile, dark mode, Try it) rather than guessing: the layout mechanics
were sound (grid columns, responsive breakpoints, no real overflow), but two things
were genuinely broken and one was under-baked. Scrolling the page with the mouse
wheel over the map zoomed the map instead of moving the page, because Leaflet's
`scrollWheelZoom` defaults on; a `window.scrollTo` sanity check showed the page could
scroll fine everywhere except over the map. The map tile filter used
`brightness(1.03) contrast(.92)` on top of grayscale/sepia, which washed street names
and road hierarchy out close to illegible. The page also read as thin before the map:
64/48px of header padding and 40px between every pill row, on top of a 480px map,
pushed the actual proof of the algorithm well below the fold on a common laptop.

**Options considered**
- Rewrite the palette/typography. Rejected: the cream/orange/serif system is the
  owner's own personal brand (see the dhyey-design-system skill), not an accident, and
  nothing about it was reported as broken; changing it would fight a deliberate choice
  rather than fix one.
- For the scroll trap: disable `scrollWheelZoom` outright (loses in-page zoom), or
  enable it on hover (still traps a scroll-past), or enable it only after a click
  inside the map and disable again on mouseleave. Chose click-to-activate: the same
  pattern most embedded-map sites use, and the only one of the three that does not
  reproduce the original bug.

**Decision** — `scrollWheelZoom: false` at map creation; a `click` listener enables it
(reusing the existing map click handler, a no-op outside sandbox mode) and a
`mouseleave` on `.map-frame` disables it again. Tile filter changed to
`sepia(.12) brightness(.97) contrast(1.12)` (dark: `sepia(.2) brightness(.88)
contrast(1.08)`), trading the brightness boost for a contrast boost. Route line
weight raised 3 to 4 (selected 5 to 6) so routes stay legible against the now-crisper
base map. Map height 480 to 560 desktop / 360 mobile, with a new 420px step at the
860px layout breakpoint; header padding and pill-row margins trimmed by about 12px
each. No HTML changes, no test changes: this is presentation only.

**Tradeoff accepted** — Click-to-activate means a first-time visitor who tries to
scroll-zoom the map without clicking first gets nothing until they click once; the
`+`/`-` buttons remain available immediately as the discoverable alternative.

**What went wrong** — The first version enabled `scrollWheelZoom` on `mouseenter`
rather than `click`. That reproduces the exact bug it was meant to fix: the cursor
enters the map the instant it is scrolled past, so the wheel event is captured before
the page ever scrolls. Caught by re-reading the two listeners against the stated goal
before it was ever shown as finished, and corrected to `click` + `mouseleave`; verified
afterwards by measuring `window.scrollY` before and after a wheel event at the same
coordinate with and without a prior click (0 to 600 without enabling; 0 and unchanged
after enabling), not by trusting the code alone.

**Scale/limits** — n/a; static asset changes only.

## [2026-09-28] Pin the map and let the cab list scroll beside it

**Problem** — The user reported the layout as broken, with a screenshot: scrolling
down past the map on a plan with several cabs showed a large empty area on the left
where the map used to be, while cab cards kept listing on the right. Cause: `.plan-detail`
is a CSS grid with two columns; grid's default `align-items: stretch` makes every
column match the height of the tallest one, so the short left column (summary + map)
was being stretched to match the cab list's height and just showing empty background
below the map. The user also asked for the page's skeleton to work like Uber's: map
as the anchor, ride list as the thing that scrolls.

**Options considered**
- Cap the cab list's height and make it scroll internally within a box matched to the
  map's height. Rejected: the map's rendered height (summary bar + map, both slightly
  variable) is awkward to mirror in CSS without JS measuring it.
- `align-items: start` alone. Fixes the empty-space bug but does not address the
  "Uber skeleton" request; the map still scrolls out of view immediately.
- `align-items: start` plus `position: sticky` on the map/summary column. Chosen: the
  grid stops stretching (bug fixed) and the map stays anchored in view while the cab
  list scrolls past beside it (matches the ask), with no JS needed.

**Decision** — `.plan-detail { align-items: start }`; `.detail-main { position: sticky;
top: 24px }`, gated to `min-width: 861px` (the layout's two-column breakpoint). Below
860px the grid collapses to one column and detail-main sits directly above the cab
list in normal flow, so sticky there would pin the map over the cards scrolling
beneath it rather than beside them; confirmed by testing before gating it (see below).

**Tradeoff accepted** — On very short desktop/tablet windows, the sticky map (roughly
650px including the summary bar) can occupy most of the visible height while the list
scrolls beside a mostly-fixed view. No layout only degrades to something worse than
before; the pre-existing responsive `#map` height steps already shrink the map on
narrower viewports.

**What went wrong**
- The first version applied `position: sticky` unconditionally. On a 375px mobile
  viewport this pinned the summary bar and map on top of the cab cards as they
  scrolled underneath, cutting off card titles and the last stop of each card behind
  the map's bottom edge. Caught by testing mobile immediately after the desktop
  screenshot looked right, not assumed safe from the desktop result alone; fixed by
  scoping sticky to the two-column breakpoint.
- After editing app.css, the running `mvn spring-boot:run` process kept serving the
  old file: Maven only copies `src/main/resources` into `target/classes` as part of a
  build goal, not on every request, so a plain resource edit needs a restart (or
  `mvn resources:resources`) to take effect. The stale build produced the exact same
  mobile overlap as before the fix, which briefly looked like the media-query change
  had not worked; confirmed the served `/app.css` still had the old rule, then
  restarted.

**Scale/limits** — n/a; static asset changes only.
