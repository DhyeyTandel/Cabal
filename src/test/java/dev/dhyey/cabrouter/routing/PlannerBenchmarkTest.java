package dev.dhyey.cabrouter.routing;

import static dev.dhyey.cabrouter.routing.Fixtures.OFFICE;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How long a full plan takes as shifts grow. Not a pass/fail test: it prints a table.
 *
 * <pre>./mvnw test -Dgroups=benchmark -DexcludedGroups=none</pre>
 */
@Tag("benchmark")
class PlannerBenchmarkTest {

    private final RoutePlanner planner = new RoutePlanner(Fixtures.TRAVEL);

    @Test
    void planningTimeBySize() {
        RoutingParams params = Fixtures.params(new Fleet(List.of(
                new Fleet.Entry(new VehicleType("SEDAN", 4), null),
                new Fleet.Entry(new VehicleType("SUV", 6), null))), 90);

        // Warm up the JIT so the first row is not dominated by compilation.
        for (int i = 0; i < 3; i++) {
            planner.plan(OFFICE, Fixtures.randomStops(new Random(i), 200, 20), params);
        }

        System.out.println("riders | cabs | sweep ms | full plan ms");
        for (int n : new int[] {50, 100, 250, 500, 1000, 2000}) {
            List<Stop> stops = Fixtures.randomStops(new Random(n), n, 20);

            long t0 = System.nanoTime();
            planner.sweepOnly(OFFICE, stops, params);
            long t1 = System.nanoTime();
            List<PlannedCab> cabs = planner.plan(OFFICE, stops, params);
            long t2 = System.nanoTime();

            System.out.printf("%6d | %4d | %8d | %12d%n", n, cabs.size(), (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);
        }
    }
}
