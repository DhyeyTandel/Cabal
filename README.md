# Cabal

*Cab + algorithm.* A REST service that plans employee transport. You give it employee home locations, a
shift time and a cab size. It decides who rides together, the order the cab collects
them in, and when each person should be at their gate. When someone cancels or books
late, it repairs the plan without reshuffling everyone else.

Under the hood this is a **heterogeneous-fleet capacitated vehicle routing problem with
a ride-time limit**: one depot (the office), many stops, a limited mix of vehicle sizes,
and a cap on how long any one person spends in the car. That problem is NP-hard, so the service uses a
pipeline of classic heuristics and measures how close they get to optimal.

Stack: Java 17, Spring Boot 4, Spring Data JPA (Hibernate 7), PostgreSQL 16, Flyway,
OSRM (optional, for road-network travel times), JUnit 5, AssertJ, MockMvc.

## Running it

Needs JDK 17+ and a local PostgreSQL. The Maven wrapper downloads everything else.

```bash
createdb cabrouter
export JAVA_HOME=/opt/homebrew/opt/openjdk@17   # or wherever your JDK 17+ lives
./mvnw spring-boot:run                          # Flyway creates the schema on startup
./scripts/demo.sh                               # seeds 24 Bengaluru employees and plans a shift
```

To time routes on real roads instead of straight lines, point it at an OSRM server
(see [Travel times](#travel-times-haversine-or-osrm)):

```bash
TRAVEL_MODEL=osrm OSRM_URL=http://localhost:5000 ./mvnw spring-boot:run
```

Database settings come from `DB_URL`, `DB_USER` and `DB_PASSWORD`. By default it
connects to `localhost:5432/cabrouter` as your OS user. Tests use in-memory H2 and need
no database:

```bash
./mvnw test
```

## API

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/offices` | Create an office (the depot) |
| `POST` | `/api/employees` | Register an employee with home coordinates, gender and optional `earliestPickup` / `latestDrop` ("HH:mm") |
| `PUT` | `/api/employees/{id}/time-window` | Set or clear an employee's time window |
| `GET` | `/api/offices/{id}/employees` | List an office's employees |
| `POST` | `/api/plans` | Plan a shift: `officeId`, `shiftTime`, `direction` (`PICKUP`/`DROP`), `employeeIds`, and optionally `fleet` (or the shorthand `cabCapacity`) and `maxRideMinutes` |
| `GET` | `/api/plans` | The 50 newest plans, as summaries |
| `GET` | `/api/plans/{id}` | Fetch a plan |
| `DELETE` | `/api/plans/{id}/employees/{empId}?strategy=LOCAL\|FULL` | Cancellation |
| `POST` | `/api/plans/{id}/employees/{empId}` | Late booking |
| `POST` | `/api/plans/{id}/replan` | Re-optimise the whole plan from scratch |
| `GET` | `/api/plans/{id}/dissolve-suggestions` | Cabs that could be dissolved, ranked by money saved |
| `POST` | `/api/plans/{id}/cabs/{cabNumber}/dissolve` | Dissolve one cab into its neighbours |
| `GET` | `/healthz` | Liveness plus a database check |
| `POST` | `/api/sandbox/plan` | Public try-it planning: up to 40 riders, nothing saved, no key needed |

When `CABAL_API_KEY` is set (always, in production), every request that changes data
needs an `X-API-Key` header. Reads never do, and neither does the sandbox, which
changes nothing.

A fleet lists vehicle types with their prices: a fixed charge per trip and a charge
per km, in whatever currency you use. Omit `available` for as many as needed:

```json
{
  "officeId": 1,
  "shiftTime": "2026-10-01T22:00:00",
  "direction": "DROP",
  "employeeIds": [1, 2, 3, 4, 5, 6, 7, 8],
  "fleet": [
    { "name": "SEDAN", "seats": 4, "costPerTrip": 800, "costPerKm": 14 },
    { "name": "SUV", "seats": 6, "available": 2, "costPerTrip": 1100, "costPerKm": 18 }
  ]
}
```

`cabCapacity: 4` is shorthand for an unlimited fleet of 4-seat cabs. Giving neither
uses `routing.default-cab-capacity`. Prices default to 1000 per trip and 15 per km,
which makes one vehicle worth about 67 km: plans then favour fewest vehicles, then
fewest km. The response gives each cab's `cost` and the plan's `totalCost`.

Errors come back as RFC 9457 problem details:

| Status | When |
|---|---|
| 400 | Bad input |
| 404 | Unknown ID |
| 409 | Duplicate booking, or a concurrent edit |
| 422 | The fleet is too small to seat everyone within the ride limit |

A plan response lists each cab's vehicle and its stops in driving order, with ETAs.
Each cab also reports `travelSource` (`HAVERSINE`, `OSRM` or `HAVERSINE_FALLBACK`),
the model that timed it. With OSRM it also carries `legs`: one polyline6-encoded road
path per route segment (stop to stop, then the last stop to the office, or the office to
the first stop), which the map draws and the playback follows. `legs` is an empty list
when unknown (haversine plans, an OSRM outage when the cab was written, or a cab stored
before geometry existed; re-plan the plan to fill it in).

```text
cab 2 SEDAN [2/4 seats, 32.17 km, longest ride 89.73 min, ESCORT]: office 22:10 -> Aditya 23:08 -> Lakshmi 23:40
```

## The heuristic

**What is minimised:** total cost. A cab costs its vehicle's trip charge, plus its
per-km charge times the route length, plus `routing.escort-cost` (600 by default) if
it needs a guard. The ride-time limit, seat counts and fleet limits are hard
constraints; cost decides everything else.

```
employees ─► sweep clustering ─► per-cab sequencing ─► escort rule ─► inter-route search ─► right-size vehicles ─► ETAs
            (who rides together) (NN, 2-opt, Or-opt)  (night safety) (relocate / swap)
```

### 1. Clustering: sweep (`SweepClusterer`)

Sort employees by their polar angle around the office, then walk round the circle
filling cabs. A cab may grow to the largest vehicle still free in the fleet. It closes
when it is full, or when adding the next person would push the longest ride over
`maxRideMinutes`, and then takes the cheapest free vehicle that seats its riders. The
result depends on where the sweep starts, so it tries up to 48 start angles in both
directions.

Filling every cab up to the biggest vehicle is not always cheapest: an SUV can cost
more than the sedan trips it replaces. So the whole search also runs once per seat
size: "any vehicle", "nothing bigger than a sedan", and so on. The **cheapest**
result wins. If no variant seats everyone in the fleet, the API returns 422 rather
than overfilling a cab.

Sweep suits this problem because every route shares one depot, and people in the same
direction from the office really do tend to share a road.

### 2. Sequencing inside a cab (`StopSequencer`)

The path is **open**: it starts at the office and ends at whichever stop is farthest.
It builds a route with nearest-neighbour, then alternates two improvement moves until
neither helps:

- **2-opt** reverses a segment of the route to remove crossings. Because the far end is
  open, it may also reverse the tail, which closed-tour 2-opt never does.
- **Or-opt** lifts out a run of 1 to 3 consecutive stops and reinserts it elsewhere,
  either way round. 2-opt cannot move one stranded stop to the other end of the route;
  Or-opt can.

Pickup and drop are handled by the **same** code. With symmetric travel times, a
pickup route is the drop route driven backwards, so the core optimises the drop
direction and the ETA step reverses it for pickups.

### 3. Night escort rule (`RoutePlanner.buildCab`)

Several Indian states require that at night a woman is never the first person picked
up or the last dropped, because she would be alone with the driver. In the outward
frame, both cases are the same position: the last stop.

"Night" is judged per cab by the time it is actually at that stop (20:00 to 07:00 by
default), not by the shift time. A 07:30 shift is a day shift, but in the demo its
Electronic City pickup is at 05:43, in the dark. `ShiftContext` computes that time
with the same arithmetic as the ETAs.

If a woman lands in that position at night, the planner tries ending the route at each
other rider instead. It accepts the
cheapest reorder that adds at most 25% distance and does not break the ride limit.
If none qualifies, the cab is flagged `escortRequired` so operations can assign a guard.

### 4. Inter-route local search (`InterRouteImprover`)

Sweep groups people by **angle only**, so someone 2 km from the office can end up with
people 15 km out on the same bearing. The demo showed this: a rider in Hebbal
Kempapura was put at the end of a Jayanagar, BTM and Koramangala cab, which cost 47
minutes. After sequencing, the planner tries two moves between each cab and its six
nearest cabs:

- **relocate**: move one rider to a cab with a free seat. If that empties the source
  cab, the plan saves a vehicle.
- **swap**: exchange two riders between cabs.

A move is kept only if total km drops, every cab stays within the ride limit, and no
new escort flag appears. Cabs keep their vehicle during the search, so a relocate only
targets a cab with a free seat.

### 5. Right-sizing vehicles (`RoutePlanner.rightSize`)

Moves change cab sizes, so vehicles are reassigned at the end. Cabs are served
fullest first, each taking the **cheapest** free vehicle that seats its riders for
that route's length.

This never paints itself into a corner. Every vehicle that fits the fullest cab also
fits every emptier one, so whichever of them the fullest cab takes, each later cab
loses one option it could equally have used. If any valid assignment exists, the
greedy pass finds one. It is not guaranteed to find the *cheapest* assignment (that is
a min-cost matching problem), but with two or three vehicle types the gap is small.

### 6. ETAs (`Timetable`)

- **Pickup** works backwards from `shiftTime - 15 min`.
- **Drop** works forwards from `shiftTime + 10 min`.

Each stop adds a 2-minute dwell. Ride time is the drive time plus the dwell at every
stop between you and the office.

All timing for a cab comes from one `Timetable`, built once when the cab is: ride
minutes, ETAs in driving order, the longest ride, and the far-end time the night
escort rule checks. The far-end time and the ETAs share one code path, so the night
check can never disagree with the ETA a rider is sent.

### 7. Traffic by time of day (`TrafficProfile`)

Travel models give **free-flow** times: empty roads. A traffic profile says how much
slower each hour of the day is, and each leg is scaled by the factor **at the moment
the cab drives it**. Factors are given per hour and interpolated in between, so 08:30
sits halfway between the 08:00 and 09:00 values.

Which moment? The one when the cab is at the leg's office-side end. For a drop that is
when it sets off on the leg, stepping forwards from departure; for a pickup it is when
it finishes the leg, stepping backwards from the required arrival. Both are already
known at that point in the calculation, so no iteration is needed.

Traffic changes timing only (ETAs, ride limits and the night check), not distance or
cost, because vendors bill by km, not by minute. It still changes plans, because the
ride limit binds harder at rush hour. In a test, the same 60 riders 15 km out need
**21 cabs at 09:30 and 15 at 23:00** under a 75-minute limit.

The default profile (`routing.traffic-hourly-factors`) is **illustrative, not
measured**: the shape of a Bengaluru weekday, from 1.2 overnight to 2.5 at 09:00 and
2.6 at 18:00. It should be calibrated from real trip logs before anyone relies on it.

### Travel times: haversine or OSRM

Everything above asks one interface, `TravelModel`, for distance and drive time. There
are two implementations:

| | `haversine` (default) | `osrm` |
|---|---|---|
| Distance | Straight line × 1.4 | Real road distance |
| Time | Distance at 45 km/h free flow, then the traffic profile | OSRM free-flow time, then the traffic profile |
| Direction | Symmetric | Directed (one-way streets, divided roads) |
| Needs | Nothing | An OSRM server |

**How OSRM is used.** Each API call fetches one distance/time matrix covering exactly
the points it touches: the office plus the riders involved. That is one request per
operation, not one per pair. OSRM servers cap coordinates per request (100 on the
public demo server), so larger shifts are split into blocks. For each pair of blocks,
one request asks for block A as sources and block B as destinations, and the pieces are
stitched into the full matrix. A test checks every stitched entry against a stub
server.

**Asymmetry.** Real roads are not symmetric, but 2-opt's segment reversal is only valid
for a symmetric cost. So the optimiser minimises the **mean of both directions'
distance**, while ride limits and ETAs use the **directed time** for the way the cab
actually drives: towards the office for pickups, away from it for drops.

**Traffic.** OSRM times assume empty roads: on the demo points its median speed is
46.9 km/h, which nobody drives across Bengaluru at 18:00. Both models therefore go
through the same time-of-day traffic profile (section 7). The haversine model's 45 km/h
free-flow speed was chosen to match OSRM's measured one.

**Failure.** If OSRM is down or errors, the call falls back to haversine and marks the
cabs `HAVERSINE_FALLBACK`. An OSRM outage makes the plan less accurate but does not
stop it being made. A pair OSRM cannot route (such as a point snapped to a disconnected
road) is estimated by haversine rather than failing the plan.

**Road geometry.** With OSRM on, each cab that is written (new or changed, never an
untouched one) also gets its route's street geometry from OSRM's route service, stored
in `cab_routes.route_legs` as one polyline6 string per line. It is purely for display:
if that request fails the cab is stored without it and the map falls back to straight
segments, and haversine mode never produces any.

**Privacy.** The OSRM request contains employees' home coordinates, so the public demo
server is not an option in production. Logs never include the request URL, for the
same reason, and OSRM is only ever called from the server, never from the browser.

**How this deployment runs it.** `deploy/deploy.sh --osrm` (script:
`deploy/setup-osrm.sh`, run as root on the server) self-hosts OSRM next to the app:

- Map data is Geofabrik's South India extract, cropped to a Bengaluru box
  (77.30,12.75 to 77.95,13.35) with `osmium`; the large download is deleted afterwards.
  The crop keeps the graph small enough for a shared server.
- The image is pinned (`ghcr.io/project-osrm/osrm-backend:v26.10.0-debian`) and the
  graph is built with the MLD pipeline (`osrm-extract`, `osrm-partition`,
  `osrm-customize`).
- One container, `cabal-osrm`, publishes `127.0.0.1:5000` only, so nothing outside the
  machine can reach it. It is capped at 768 MB, restarts with Docker, and
  `--max-table-size 100` matches `routing.osrm.max-table-size`.
- The script checks a real test route, sets `TRAVEL_MODEL=osrm` and `OSRM_URL` in
  `/etc/cabal/cabal.env`, restarts the service, and re-plans every stored plan so the
  stored routes use roads.
- It never installs Docker and never touches another container or service. Re-running
  it refreshes the map data; the old graph keeps serving until the new one is built.

If OSRM goes down, plans fall back to haversine (`HAVERSINE_FALLBACK`) and the map
draws straight segments until it is back. For a different city, change the box in the
script. Elsewhere, the OSRM project's own steps work: `osrm-extract -p /opt/car.lua`,
`osrm-partition`, `osrm-customize`, then `osrm-routed --algorithm mld`, with
`OSRM_URL` pointing at it and `routing.osrm.max-table-size` set to match.

### Re-planning

| | `LOCAL` (default) | `FULL` |
|---|---|---|
| Cancellation | Re-sequence only the affected cab; drop it if empty | Re-cluster everyone who is left |
| Other drivers | Untouched, same cab number and same stops | May change |
| Vehicles | The affected cab keeps its vehicle (it is already dispatched) | Re-assigned and right-sized |
| Distance | Can drift from optimal over many edits | Best the heuristic can do |

Late bookings use **cheapest insertion**, by cost. Three kinds of option compete:

- add the rider to a cab with a free seat;
- **upgrade** a full cab to a bigger free vehicle and add them there;
- send a new cab in the cheapest free vehicle.

Options that break the ride limit are skipped, and one that would newly need a guard
loses to any that would not. If nothing is feasible, the API returns 422. An upgrade
does mean swapping a car that may already be assigned, which is why it only wins when
it is cheaper than sending another one. `POST /replan` exists for when enough local edits have piled up that a
reshuffle is worth the disruption.

In practice, stability matters more than a few kilometres once drivers and riders have
been notified. So the cheap, predictable repair is the default, and the full
re-optimisation is an explicit choice.

**Time windows.** An employee can say "do not pick me up before 06:30"
(`earliestPickup`, used on pickup plans) or "get me home by 23:30" (`latestDrop`, used
on drop plans). Because pickups are timed backwards from the office arrival and drops
forwards from departure, both come down to a per-rider cap on the ride, so they are
enforced wherever the ride limit is: the sweep, insertion, the inter-route search, the
escort reorder and dissolving. The check uses the same rounding as the published ETA,
so a rider shown at 06:30 is never judged 06:29.6, and a drop window past midnight
("by 00:30" for a 22:10 departure) resolves to the next day. Like the ride limit, a
lone rider always gets a cab; if even that cannot meet their window, the stop is
flagged `windowMissed` rather than dropped. Windows are read live from the employee, so
a changed preference applies at the plan's next edit.

On the demo, "Rohan not before 06:15" moved his pickup from 05:53 to 06:19 and
"Vikram home by 23:15" was already met (23:07). "Lakshmi not before 06:00" cannot be
met at all: alone, Electronic City to the office at morning traffic needs a 05:51
pickup to arrive by 07:15, so she rides solo and is flagged. Windows cost money: that
07:30 pickup went from 5 cabs and 7,163 to 7 cabs and 8,317.

**Dissolving a cab** is the middle path for plans that local repairs have thinned out.
A cab can be dissolved when every one of its riders fits into one of its six nearest
cabs, using a free seat or an upgrade to a free bigger vehicle (the dissolved cab's
own vehicle counts as free), without breaking a ride limit, without a new night guard,
and for less money overall. Riders move farthest from the office first, each by
cheapest insertion. Only the dissolved cab and the cabs receiving its riders change;
its number becomes a gap.

Suggestions are alternatives, not a to-do list: applying one usually invalidates the
others. So applying recomputes against the plan as it is now, and returns 409 if the
dissolve no longer works. On the demo pickup, after four cancellations left two cabs
at 2/4 and 1/4, the top suggestion dissolved a full 4-rider sedan into them and saved
857 of 6,794 (cost went to 5,936), leaving every other cab's riders and ETAs as they
were.

## Measured quality

From the test suite (fixed seeds, so these numbers are reproducible):

| Check | Result |
|---|---|
| Per-cab sequencing vs **brute-force optimum**, 500 random cabs of 2 to 7 stops | 2-opt alone: 412/500 optimal, mean gap 0.84%, worst 23.3% |
| Same, with Or-opt added | **471/500 optimal, mean gap 0.19%, worst 12.6%** |
| Inter-route pass vs sweep alone, 20 instances of 60 riders | 3.5% less total distance, never more cabs |
| Demo drop, 23 riders, sedans 800 + 14/km and 2 SUVs 1,100 + 18/km | 7,613 by cost, against 8,037 for the plan the earlier fewest-vehicles objective chose: **5.3% cheaper**. It uses one SUV instead of two |
| Demo shift (23 riders, Bengaluru), 4-seat cabs only | 7 cabs, 130.8 km |
| Same riders, sedans plus 2 six-seat SUVs | 6 vehicles instead of 7 |
| Same 22:00 drop with the time-of-day traffic profile | 5 vehicles, 6,744, and no guard needed |

The first four demo rows were measured with a flat all-day speed (22 km/h), before the
traffic profile existed. With the profile, late-night roads run at about 1.4× free
flow instead of about 2×. Longer routes then fit in the 90-minute limit, so the drop
needs one vehicle fewer. That extra room also lets the escort rule reorder the
Electronic City cab so its last drop is not a woman alone, instead of sending a guard.

The inter-route pass is what moved the Hebbal Kempapura rider out of the southern cab.
When it was first added, it took the demo shift from 136.5 km to 128.2 km. The figure
is 130.8 km now because later changes (the time-based night rule and a reordered
search) settle in a slightly different local optimum.

**How good is the haversine model?** Checked against OSRM's real road network for all
600 ordered pairs among the 25 demo points (office plus 24 homes):

| Assumption | Real roads |
|---|---|
| Road distance = straight line × 1.4 | Median ratio **1.37**, but p10 1.23, p90 1.61 and worst **4.34** |
| Drive time is the same both ways | Median difference 4.6%, p90 14.2% |

So the 1.4 factor is well calibrated on average but can be badly wrong for a specific
pair, such as two homes on opposite sides of a lake or rail line. That is exactly the
case where OSRM changes the plan.

The brute-force comparison is what gives the heuristic claims weight: the test would
fail if the sequencer ever reported a route shorter than the true optimum (an
arithmetic bug) or longer than its own starting point.

## Performance

Planning time for a full shift from scratch (sweep plus all improvement passes), on an
Apple Silicon laptop, random riders within 20 km, sedans and SUVs, 90-minute ride
limit. Reproduce with `./mvnw test -Dgroups=benchmark -DexcludedGroups=none`.

| Riders | Cabs | Sweep only | Full plan, first version | Full plan, after speed-up | Full plan, with costs |
|---|---|---|---|---|---|
| 100 | 17 | 73 ms | 428 ms | 133 ms | 153 ms |
| 250 | 42 | 182 ms | 1.7 s | 0.42 s | 0.48 s |
| 500 | 84 | 366 ms | 3.9 s | 1.1 s | 1.2 s |
| 1,000 | 167 | 743 ms | 15.6 s | 3.7 s | 4.0 s |
| 2,000 | 334 | 1.5 s | 46.0 s | 12.1 s | 13.1 s |

The cost model added about 9%, because the sweep now runs once per seat size (twice
for sedans plus SUVs).

Sweep is linear. The inter-route search dominates, and three changes made it about
4× faster:

1. **Only re-check what changed.** A cab pair is revisited only if one of the cabs
   changed in the last pass. This alone took 1,000 riders from 15.6 s to 6.5 s.
2. **Each pair once.** Neighbour lists are mostly mutual, so pairs were being tried in
   both orders.
3. **Prune hopeless moves on large shifts.** A rider is only tried in another cab if
   they are within 1.5× the distance to its centre as to their own cab's centre.
   Measured on 1,000 riders:

   | Filter slack | Time | Saving from inter-route pass |
   |---|---|---|
   | off | 6.5 s | 3.6% |
   | 2.0 | 4.7 s | 3.6% |
   | **1.5 (chosen)** | **3.8 s** | **3.6%** |
   | 1.0 | 2.7 s | 3.0% |

   1.5 is the tightest value that keeps the full saving. Shifts of 40 cabs or fewer
   skip the filter entirely: they plan in milliseconds regardless, and on the 23-rider
   demo the filter cost 1.6% distance.

Cancellations and late bookings do not run the full search, so they stay fast at any
size. With OSRM, add one matrix request per operation.

## Limits

These are honest gaps, roughly in the order I would fix them:

1. **Traffic is one citywide curve, and uncalibrated.** Time of day is modelled, but
   every road gets the same factor at a given hour, and the default factors are
   assumptions. An outer ring road and a residential lane do not congest alike.
   Per-road speeds (OSRM supports custom segment speeds) fitted to real GPS trip logs,
   or a traffic-aware matrix API, would fix both. There is no live traffic: OSRM times
   are free-flow and the time-of-day curve is the only congestion model. Without OSRM,
   straight-line distances can also be off by up to 4× for individual pairs (measured
   above).
2. **No global optimality guarantee.** Sweep plus local search finds good plans, not
   optimal ones. For large shifts, a metaheuristic (simulated annealing, or ALNS as used
   in production VRP solvers) or a solver like OR-Tools would do better.
3. **Pricing is simple.** Real vendor contracts have minimum-km slabs, night
   surcharges and waiting charges. The model is trip charge plus a per-km rate, and a
   flat guard cost. Vehicle assignment is greedy, not an exact min-cost matching.
4. **No vendor-yard depot deadhead.** Cabs are assumed to start at their first stop, with no drive from the vendor's yard. Employee pickup and drop time windows are supported; see [Time windows](#re-planning).
5. **Local repair drifts.** Many cancellations in a row leave half-empty cabs, still in
   their original vehicles. Dissolve suggestions recover much of this, but only one cab
   at a time and only into its six nearest neighbours; pairs of cabs are not merged
   into a new vehicle, and suggestions are not chained.
6. **Large shifts take seconds.** A 2,000-rider full re-plan takes about 13 s. That is
   fine for planning ahead of a shift, but too slow to run on every edit, which is
   why edits use local repair. Beyond that, split by zone, or run the search under a
   time budget.

Fixed since the first version: plans now use a mixed, limited fleet; the escort rule
checks each cab's actual first-pickup or last-drop time instead of the shift time; and
travel can be timed on the real road network through OSRM, with directed times;
planning is about 4× faster on large shifts; plans minimise cost with per-vehicle
prices; late bookings can upgrade a full cab to a bigger free vehicle; and each leg is
timed with the traffic at the hour it is driven.

## Deployment

It runs at **https://cabal.dhyeytandel.in** on a small Linux server, reached through a
Cloudflare Tunnel. The page at `/` is a dispatcher's line sheet: a full-height map with one panel beside it
(a snapping bottom sheet on phones). Every cab is drawn as a transit line on a shared
shift clock, with riders as stations at their ETAs and the office as the terminus, so
grouping, stop order and timing read at a glance. A split-flap board summarises the
plan, and selecting a cab opens its stop diagram and a ledger of the rules it obeyed
(seats, ride limit, time windows, night guard). On load the shift plays itself: cabs
move along their routes while the board counts riders on board; the ruler scrubs it
by hand.

- **Demo plans:** the seeded 07:30 pickup and 22:00 drop.
- **Try it live:** visitors drop up to 40 riders on the map (or add 20 at random, or
  mark the next pins as women to see the night rule), pick a shift and a fleet, and
  the real engine plans it on the server in well under a second. Nothing is saved.

Anyone can read the API and use the sandbox; only the API key holder can change
stored data.

```bash
export DEPLOY_HOST=user@your-server   # e.g. in your shell profile; kept out of this repo
deploy/deploy.sh --setup --seed      # first time: Java, PostgreSQL, user, secrets, service, demo data
deploy/deploy.sh                     # every release after that
deploy/deploy.sh --osrm              # once: self-host OSRM; again to refresh the map data
```

`deploy.sh` builds and tests on the Mac, copies the jar to the server over SSH,
restarts the service, and rolls back to the previous jar if the new one
does not answer `/healthz` within 90 seconds. It asks for the server's sudo password
once. `--osrm` additionally runs `setup-osrm.sh` after the install; flags combine
(`--setup --seed --osrm`). Everything it installs lives in `deploy/`:

| File | Runs on | What it does |
|---|---|---|
| `deploy.sh` | Mac | Build, test, upload, trigger install |
| `setup-server.sh` | Server, once | Java, PostgreSQL, `cabal` user, database, secrets in `/etc/cabal/cabal.env`, systemd unit. Safe to rerun; keeps existing secrets |
| `install-release.sh` | Server | Swap the jar, restart, health-check, roll back on failure, optional demo seed |
| `setup-osrm.sh` | Server | Self-hosted OSRM: Bengaluru map crop, pinned Docker image on `127.0.0.1:5000`, switch Cabal to it, re-plan stored plans. Safe to rerun; refreshes the map data |
| `cabal.service` | Server | The systemd unit |
| `cloudflare-tunnel.md` | Reference | How to add the dashboard-managed tunnel route |

**Kept light.** Measured on this Mac with the demo plus a 500-rider plan:

| JVM setup | Memory after load | 500-rider plan |
|---|---|---|
| Default | 362 MB, still growing | 0.5 s |
| Production (`cabal.service`: 256 MB heap, serial GC, capped metaspace and code cache) | about 250 to 300 MB | 0.6 s |

On the server, systemd caps the whole process at 512 MB (`MemoryMax`) and gives it a
lower CPU weight than everything else, so it cannot starve the other services sharing
the machine. The database pool is 5 connections and Tomcat 20 threads.

**Safe to expose.**
- The app listens on `127.0.0.1` only. The Cloudflare Tunnel is the only way in, so no
  inbound port is open and the server's IP address is not published.
- Every request that could change data (any method other than GET, HEAD or OPTIONS,
  on any path) needs `X-API-Key`. The check is deliberately not a path prefix: the raw
  request URI can differ from the path Spring routes on, and `/%61pi/offices` really
  does reach `/api/offices`. Tested against the real server.
- A plan can hold at most 300 riders in production, so no single request can tie up
  the CPU (2,000 riders take about 12 s).
- The public sandbox is exempt from the key on exactly one raw path,
  `/api/sandbox/plan`; any other spelling still needs the key (tested: trailing slash,
  doubled slash, encoded letter, `..`). It never touches the database, takes at most 40
  riders within 25 km, rejects bodies over 32 KB (413) or of undeclared length (411)
  before anything parses them, allows 10 plans per minute per visitor and 3 at once,
  with up to 10 more waiting at most 2 seconds for a slot (429 with `Retry-After`
  beyond that). The waiting cap is kept well under Tomcat's 20 request threads, so a
  sandbox flood can never occupy every thread and stall ordinary page loads.
- The page inserts all API data with `textContent`, never `innerHTML`. Leaflet is
  pinned with subresource integrity hashes.
- The service runs as a no-login `cabal` user under systemd sandboxing (read-only
  system, no home access, no new privileges, no capabilities).
- Every response carries a Content-Security-Policy (only this site, the Leaflet CDN,
  Google Fonts and OpenStreetMap tiles), `nosniff`, frame blocking, a referrer policy
  and a permissions policy. Error responses never include exception details, and
  `/error` requested directly is a plain 404.
- Names longer than their database columns are rejected with 400 before reaching the
  database.

**Other services on the machine.** The server runs other things too. None of them are
routed through the tunnel, and setup never touches them.

OSRM runs there as the one container this project adds (`cabal-osrm`, localhost only,
768 MB cap), started by `deploy.sh --osrm`. Without it the app still works: set
`TRAVEL_MODEL=haversine` and it needs no extra memory (see
[Travel times](#travel-times-haversine-or-osrm)).

## Code layout

```
routing/   the algorithm: plain Java, no Spring, unit-tested in isolation
  ShiftPlan: the plan-editing module (create, cancel, add, replan); the rest of
  routing/ sits behind it
  PlanningPolicy: turns configuration into the settings for one plan (office time,
  night window, traffic, defaults), built once at startup
  CabBuilder: builds one cab (sequence, night escort repair, timetable); used by
  both the planner and the inter-route search
  SweepClusterer, StopSequencer, InterRouteImprover, RoutePlanner, Timetable,
  Fleet, FleetInventory, ShiftContext, TrafficProfile, TravelModel (+ HaversineTravelModel,
  MatrixTravelModel), RouteMetrics, value records
travel/    where travel times come from: HaversineProvider, OsrmProvider + OsrmClient
           (matrix fetching, chunking, fallback)
domain/    JPA entities (Office, Employee, RoutePlan + PlanVehicleType, CabRoute,
           RouteStop) and repositories
service/   PlanningService (loads entities, calls ShiftPlan, saves changed cabs), DirectoryService
api/       REST controllers, request/response records, problem-detail error mapping
config/    RoutingProperties (all tunables in application.properties), read only here to
           build PlanningPolicy and the travel-model provider
static/    the demo page: index.html, app.css, app.js (Leaflet map, no build step)
db/migration/   schema, owned by Flyway; Hibernate only validates it
  V1__init.sql          initial schema
  V2__mixed_fleet.sql   adds fleets and migrates existing plans in place
  V3__travel_source.sql records which travel model timed each cab
  V4__costs.sql         vehicle prices per plan and a cost per cab, backfilled
```

Other design choices worth knowing:

- **Plan-editing rules live in the routing core.** `ShiftPlan` owns local vs full
  re-planning, cab numbering, vehicle retention and driving order, and is tested in
  milliseconds without HTTP or a database. `PlanningService` only loads, calls and
  saves, writing just the cabs that changed.
- **The routing core has no framework dependency.** It can be tested in milliseconds
  and lifted into a batch job or another service unchanged.
- **Stops snapshot coordinates.** A route stop copies the employee's lat/lng, so moving
  house does not silently rewrite a plan that has already been issued.
- **Optimistic locking.** `RoutePlan` has a `@Version` column, and every edit bumps
  `revision`. Two dispatchers editing the same plan get a 409 instead of a lost update.
- **Open-session-in-view is off.** Responses are mapped inside the transaction, and
  batch fetching avoids N+1 queries on cabs and stops.
