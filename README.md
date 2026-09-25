# Cabal

*Cab + algorithm.* A REST service that plans employee transport. You give it employee home locations, a
shift time and a cab size. It decides who rides together, the order the cab collects
them in, and when each person should be at their gate. When someone cancels or books
late, it repairs the plan without reshuffling everyone else.

Under the hood this is a **capacitated vehicle routing problem with a ride-time limit**:
one depot (the office), many stops, a fixed seat count per cab, and a cap on how long
any one person spends in the car. That problem is NP-hard, so the service uses a
pipeline of classic heuristics and measures how close they get to optimal.

Stack: Java 17, Spring Boot 4, Spring Data JPA (Hibernate 7), PostgreSQL 16, Flyway,
JUnit 5, AssertJ, MockMvc.

## Running it

Needs JDK 17+ and a local PostgreSQL. The Maven wrapper downloads everything else.

```bash
createdb cabrouter
export JAVA_HOME=/opt/homebrew/opt/openjdk@17   # or wherever your JDK 17+ lives
./mvnw spring-boot:run                          # Flyway creates the schema on startup
./scripts/demo.sh                               # seeds 24 Bengaluru employees and plans a shift
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
| `POST` | `/api/plans` | Plan a shift: `officeId`, `shiftTime`, `direction` (`PICKUP`/`DROP`), `employeeIds`, optional `cabCapacity`, `maxRideMinutes` |
| `GET` | `/api/plans/{id}` | Fetch a plan |
| `DELETE` | `/api/plans/{id}/employees/{empId}?strategy=LOCAL\|FULL` | Cancellation |
| `POST` | `/api/plans/{id}/employees/{empId}` | Late booking |
| `POST` | `/api/plans/{id}/replan` | Re-optimise the whole plan from scratch |

Errors come back as RFC 9457 problem details: 400 for bad input, 404 for unknown IDs,
and 409 for a duplicate booking or a concurrent edit.

A plan response lists each cab's stops in driving order, with ETAs:

```text
cab 7 [3 seats, 12.22 km, longest ride 37.34 min]: Ravi 20:06 -> Suresh 20:26 -> Anjali 20:39 -> office 20:45
```

## The heuristic

```
employees ──► sweep clustering ──► per-cab sequencing ──► escort rule ──► inter-route search ──► ETAs
              (who rides together)  (NN, 2-opt, Or-opt)   (night safety)  (relocate / swap)
```

### 1. Clustering: sweep (`SweepClusterer`)

Sort employees by their polar angle around the office, then walk round the circle
filling cabs. A cab closes when it is full, or when adding the next person would push
the longest ride over `maxRideMinutes`. The result depends on where the sweep starts,
so it tries up to 48 start angles in both directions. The winner uses the **fewest
cabs**, then the **fewest kilometres**, because a vehicle costs far more than a few
extra km.

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

Several Indian states require that on night shifts a woman is never the first person
picked up or the last dropped, because she would be alone with the driver. In the
outward frame, both cases are the same position: the last stop. If a woman lands
there, the planner tries ending the route at each other rider instead. It accepts the
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
new escort flag appears.

### 5. ETAs (`EtaCalculator`)

- **Pickup** works backwards from `shiftTime - 15 min`.
- **Drop** works forwards from `shiftTime + 10 min`.

Each stop adds a 2-minute dwell. Ride time is the drive time plus the dwell at every
stop between you and the office.

### Re-planning

| | `LOCAL` (default) | `FULL` |
|---|---|---|
| Cancellation | Re-sequence only the affected cab; drop it if empty | Re-cluster everyone who is left |
| Other drivers | Untouched, same cab number and same stops | May change |
| Distance | Can drift from optimal over many edits | Best the heuristic can do |

Late bookings use **cheapest insertion**: try the rider in every cab with a free seat,
keep the one whose route grows least within the ride limit, and open a new cab only if
none fits. `POST /replan` exists for when enough local edits have piled up that a
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
| Inter-route pass vs sweep alone, 20 instances of 60 riders | 3.6% less total distance, never more cabs |
| Demo shift (23 riders, Bengaluru) | 136.5 km to 128.2 km with the same 7 cabs |

The brute-force comparison is what gives the heuristic claims weight: the test would
fail if the sequencer ever reported a route shorter than the true optimum (an
arithmetic bug) or longer than its own starting point.

## Limits

These are honest gaps, roughly in the order I would fix them:

1. **Straight-line travel times.** Distance is haversine × 1.4 at a flat 22 km/h. Real
   Bengaluru traffic varies hugely by road and hour, and a river or flyover can make
   a 2 km neighbour a 20-minute drive. `TravelModel` is an interface precisely so a
   road-network matrix (OSRM, Google Distance Matrix) can replace it. Everything
   downstream assumes symmetry, which real traffic breaks, so an asymmetric model
   would also need direction-aware 2-opt.
2. **No global optimality guarantee.** Sweep plus local search finds good plans, not
   optimal ones. For large shifts, a metaheuristic (simulated annealing, or ALNS as used
   in production VRP solvers) or a solver like OR-Tools would do better.
3. **Homogeneous fleet.** Every cab has the same capacity. Real fleets mix 4-seat
   sedans and 6-seat SUVs at different costs.
4. **Night is judged by shift time.** A 07:30 shift is "day", but its first pickup may
   be at 06:15 in the dark. The rule should check each cab's actual first pickup or
   last drop time.
5. **No time windows or vehicle availability.** Employees cannot say "not before
   07:00". Cabs are assumed unlimited and to start at the first stop, with no
   depot-to-first-stop deadhead.
6. **Local repair drifts.** Many cancellations in a row leave half-empty cabs. Nothing
   yet suggests "merge cabs 4 and 7" automatically.
7. **Performance is untested beyond a few hundred riders.** The sweep is roughly
   O(starts × n × c²) and the inter-route pass roughly O(cabs × 6 × c²) per pass,
   where c is cab size. Fine for a shift, but not benchmarked at city scale.

## Code layout

```
routing/   the algorithm: plain Java, no Spring, unit-tested in isolation
  SweepClusterer, StopSequencer, InterRouteImprover, RoutePlanner, EtaCalculator,
  TravelModel (+ HaversineTravelModel), RouteMetrics, value records
domain/    JPA entities (Office, Employee, RoutePlan, CabRoute, RouteStop) and repositories
service/   PlanningService (entities to routing and back), DirectoryService
api/       REST controllers, request/response records, problem-detail error mapping
config/    RoutingProperties (all tunables in application.properties)
db/migration/V1__init.sql   schema, owned by Flyway; Hibernate only validates it
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
