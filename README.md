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
| `POST` | `/api/employees` | Register an employee with home coordinates and gender |
| `GET` | `/api/offices/{id}/employees` | List an office's employees |
| `POST` | `/api/plans` | Plan a shift: `officeId`, `shiftTime`, `direction` (`PICKUP`/`DROP`), `employeeIds`, and optionally `fleet` (or the shorthand `cabCapacity`) and `maxRideMinutes` |
| `GET` | `/api/plans/{id}` | Fetch a plan |
| `DELETE` | `/api/plans/{id}/employees/{empId}?strategy=LOCAL\|FULL` | Cancellation |
| `POST` | `/api/plans/{id}/employees/{empId}` | Late booking |
| `POST` | `/api/plans/{id}/replan` | Re-optimise the whole plan from scratch |

A fleet lists vehicle types. Omit `available` for as many as needed:

```json
{
  "officeId": 1,
  "shiftTime": "2026-10-01T22:00:00",
  "direction": "DROP",
  "employeeIds": [1, 2, 3, 4, 5, 6, 7, 8],
  "fleet": [
    { "name": "SEDAN", "seats": 4 },
    { "name": "SUV", "seats": 6, "available": 2 }
  ]
}
```

`cabCapacity: 4` is shorthand for an unlimited fleet of 4-seat cabs. Giving neither
uses `routing.default-cab-capacity`.

Errors come back as RFC 9457 problem details:

| Status | When |
|---|---|
| 400 | Bad input |
| 404 | Unknown ID |
| 409 | Duplicate booking, or a concurrent edit |
| 422 | The fleet is too small to seat everyone within the ride limit |

A plan response lists each cab's vehicle and its stops in driving order, with ETAs.
Each cab also reports `travelSource` (`HAVERSINE`, `OSRM` or `HAVERSINE_FALLBACK`),
the model that timed it:

```text
cab 2 SEDAN [2/4 seats, 32.17 km, longest ride 89.73 min, ESCORT]: office 22:10 -> Aditya 23:08 -> Lakshmi 23:40
```

## The heuristic

```
employees ─► sweep clustering ─► per-cab sequencing ─► escort rule ─► inter-route search ─► right-size vehicles ─► ETAs
            (who rides together) (NN, 2-opt, Or-opt)  (night safety) (relocate / swap)
```

### 1. Clustering: sweep (`SweepClusterer`)

Sort employees by their polar angle around the office, then walk round the circle
filling cabs. A cab may grow to the largest vehicle still free in the fleet. It closes
when it is full, or when adding the next person would push the longest ride over
`maxRideMinutes`, and then takes the smallest free vehicle that seats its riders. The
result depends on where the sweep starts, so it tries up to 48 start angles in both
directions. The winner uses the **fewest vehicles**, then the **fewest kilometres**,
because a vehicle costs far more than a few extra km. If no start angle seats everyone
in the fleet, the API returns 422 rather than overfilling a cab.

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
fullest first, each taking the smallest vehicle still free that seats its riders.
Every vehicle that fits the fullest cab also fits every emptier one, so taking the
smallest fit never blocks a later cab. That makes this greedy pass exact: if any valid
assignment exists, it finds one.

### 6. ETAs (`EtaCalculator`)

- **Pickup** works backwards from `shiftTime - 15 min`.
- **Drop** works forwards from `shiftTime + 10 min`.

Each stop adds a 2-minute dwell. Ride time is the drive time plus the dwell at every
stop between you and the office.

### Travel times: haversine or OSRM

Everything above asks one interface, `TravelModel`, for distance and drive time. There
are two implementations:

| | `haversine` (default) | `osrm` |
|---|---|---|
| Distance | Straight line × 1.4 | Real road distance |
| Time | Distance at a flat 22 km/h | OSRM free-flow time × 2.0 for traffic |
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

**Traffic.** OSRM times assume empty roads. On the demo points, its median speed is
46.9 km/h, which nobody drives across Bengaluru at 18:00. So times are multiplied by
`routing.osrm.duration-factor` (2.0 by default).

**Failure.** If OSRM is down or errors, the call falls back to haversine and marks the
cabs `HAVERSINE_FALLBACK`. An OSRM outage makes the plan less accurate but does not
stop it being made. A pair OSRM cannot route (such as a point snapped to a disconnected
road) is estimated by haversine rather than failing the plan.

**Privacy.** The OSRM request contains employees' home coordinates. Do not send those
to the public demo server in production: run your own. Logs never include the request
URL, for the same reason. To self-host for Bengaluru:

```bash
wget https://download.geofabrik.de/asia/india/southern-zone-latest.osm.pbf   # ~560 MB
docker run -t -v "$PWD:/data" ghcr.io/project-osrm/osrm-backend osrm-extract -p /opt/car.lua /data/southern-zone-latest.osm.pbf
docker run -t -v "$PWD:/data" ghcr.io/project-osrm/osrm-backend osrm-partition /data/southern-zone-latest.osrm
docker run -t -v "$PWD:/data" ghcr.io/project-osrm/osrm-backend osrm-customize /data/southern-zone-latest.osrm
docker run -t -p 5000:5000 -v "$PWD:/data" ghcr.io/project-osrm/osrm-backend osrm-routed --algorithm mld --max-table-size 1000 /data/southern-zone-latest.osrm
```

Then set `OSRM_URL=http://localhost:5000` and raise `routing.osrm.max-table-size` to
match. (These are the OSRM project's standard steps. The service itself was tested
against the public demo server and a stub, not a self-hosted instance.)

### Re-planning

| | `LOCAL` (default) | `FULL` |
|---|---|---|
| Cancellation | Re-sequence only the affected cab; drop it if empty | Re-cluster everyone who is left |
| Other drivers | Untouched, same cab number and same stops | May change |
| Vehicles | The affected cab keeps its vehicle (it is already dispatched) | Re-assigned and right-sized |
| Distance | Can drift from optimal over many edits | Best the heuristic can do |

Late bookings use **cheapest insertion**: try the rider in every cab with a free seat,
keep the one whose route grows least within the ride limit, and open a new cab in the
smallest free vehicle only if none fits. If the fleet has nothing left, the API returns
422. `POST /replan` exists for when enough local edits have piled up that a
reshuffle is worth the disruption.

In practice, stability matters more than a few kilometres once drivers and riders have
been notified. So the cheap, predictable repair is the default, and the full
re-optimisation is an explicit choice.

## Measured quality

From the test suite (fixed seeds, so these numbers are reproducible):

| Check | Result |
|---|---|
| Per-cab sequencing vs **brute-force optimum**, 500 random cabs of 2 to 7 stops | 2-opt alone: 412/500 optimal, mean gap 0.84%, worst 23.3% |
| Same, with Or-opt added | **471/500 optimal, mean gap 0.19%, worst 12.6%** |
| Inter-route pass vs sweep alone, 20 instances of 60 riders | 3.5% less total distance, never more cabs |
| Demo shift (23 riders, Bengaluru), 4-seat cabs only | 7 cabs, 130.8 km |
| Same riders, sedans plus 2 six-seat SUVs | 6 vehicles instead of 7 |

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

| Riders | Cabs | Sweep only | Full plan, first version | Full plan, now |
|---|---|---|---|---|
| 100 | 17 | 55 ms | 428 ms | 133 ms |
| 250 | 42 | 135 ms | 1.7 s | 0.42 s |
| 500 | 84 | 273 ms | 3.9 s | 1.1 s |
| 1,000 | 167 | 583 ms | 15.6 s | 3.7 s |
| 2,000 | 334 | 1.1 s | 46.0 s | 12.1 s |

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

1. **No live traffic.** With OSRM, distances and road topology are real, but traffic is
   one flat multiplier. A 07:00 pickup and a 09:30 one on the same road get the same
   factor. Time-of-day speed profiles (OSRM supports custom segment speeds) or a
   traffic-aware matrix API would fix this. Without OSRM, straight-line estimates can
   be off by up to 4× for individual pairs (measured above).
2. **No global optimality guarantee.** Sweep plus local search finds good plans, not
   optimal ones. For large shifts, a metaheuristic (simulated annealing, or ALNS as used
   in production VRP solvers) or a solver like OR-Tools would do better.
3. **Vehicles are counted, not costed.** The objective is fewest vehicles, then fewest
   km. It does not know that an SUV costs more per km than a sedan, so it can pick one
   SUV where two sedans would be cheaper. A per-type fixed and per-km cost would fix it.
4. **Late bookings never upgrade a vehicle.** If every sedan is full but an SUV is
   free, insertion opens a new cab instead of swapping a full sedan for the SUV.
5. **No time windows or depot deadhead.** Employees cannot say "not before 07:00".
   Cabs are assumed to start at their first stop, with no drive from the vendor's yard.
6. **Local repair drifts.** Many cancellations in a row leave half-empty cabs, still in
   their original vehicles. Nothing yet suggests "merge cabs 4 and 7" automatically.
7. **Large shifts take seconds.** A 2,000-rider full re-plan takes about 12 s. That is
   fine for planning ahead of a shift, but too slow to run on every edit, which is
   why edits use local repair. Beyond that, split by zone, or run the search under a
   time budget.

Fixed since the first version: plans now use a mixed, limited fleet; the escort rule
checks each cab's actual first-pickup or last-drop time instead of the shift time; and
travel can be timed on the real road network through OSRM, with directed times; and
planning is about 4× faster on large shifts, with measured numbers.

## Code layout

```
routing/   the algorithm: plain Java, no Spring, unit-tested in isolation
  SweepClusterer, StopSequencer, InterRouteImprover, RoutePlanner, EtaCalculator,
  Fleet, FleetInventory, ShiftContext, TravelModel (+ HaversineTravelModel,
  MatrixTravelModel), RouteMetrics, value records
travel/    where travel times come from: HaversineProvider, OsrmProvider + OsrmClient
           (matrix fetching, chunking, fallback)
domain/    JPA entities (Office, Employee, RoutePlan + PlanVehicleType, CabRoute,
           RouteStop) and repositories
service/   PlanningService (entities to routing and back), DirectoryService
api/       REST controllers, request/response records, problem-detail error mapping
config/    RoutingProperties (all tunables in application.properties)
db/migration/   schema, owned by Flyway; Hibernate only validates it
  V1__init.sql          initial schema
  V2__mixed_fleet.sql   adds fleets and migrates existing plans in place
  V3__travel_source.sql records which travel model timed each cab
```

Other design choices worth knowing:

- **The routing core has no framework dependency.** It can be tested in milliseconds
  and lifted into a batch job or another service unchanged.
- **Stops snapshot coordinates.** A route stop copies the employee's lat/lng, so moving
  house does not silently rewrite a plan that has already been issued.
- **Optimistic locking.** `RoutePlan` has a `@Version` column, and every edit bumps
  `revision`. Two dispatchers editing the same plan get a 409 instead of a lost update.
- **Open-session-in-view is off.** Responses are mapped inside the transaction, and
  batch fetching avoids N+1 queries on cabs and stops.
