package dev.dhyey.cabrouter.config;

import dev.dhyey.cabrouter.routing.HaversineTravelModel;
import dev.dhyey.cabrouter.routing.PlanningPolicy;
import dev.dhyey.cabrouter.routing.TrafficProfile;
import dev.dhyey.cabrouter.routing.TravelModelProvider;
import dev.dhyey.cabrouter.travel.HaversineProvider;
import dev.dhyey.cabrouter.travel.OsrmClient;
import dev.dhyey.cabrouter.travel.OsrmProvider;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RoutingProperties.class)
public class RoutingConfig {

    /** Haversine unless {@code routing.travel-model=osrm}; OSRM still uses haversine as its fallback. */
    @Bean
    TravelModelProvider travelModelProvider(RoutingProperties props) {
        HaversineTravelModel haversine = new HaversineTravelModel(props.circuityFactor(), props.freeFlowSpeedKmph());
        if (props.travelModel() != RoutingProperties.TravelModelKind.OSRM) {
            return new HaversineProvider(haversine);
        }
        RoutingProperties.Osrm osrm = props.osrm();
        OsrmClient client = new OsrmClient(osrm.baseUrl(), osrm.maxTableSize(), Duration.ofSeconds(osrm.timeoutSeconds()));
        return new OsrmProvider(client, haversine);
    }

    /** The whole routing policy, assembled once at startup from {@code routing.*} properties. */
    @Bean
    PlanningPolicy planningPolicy(RoutingProperties props) {
        return new PlanningPolicy(
                props.dwellMinutes(),
                props.arrivalBufferMinutes(),
                props.departureBufferMinutes(),
                props.nightStartHour(),
                props.nightEndHour(),
                new TrafficProfile(props.trafficHourlyFactors()),
                props.escortDetourTolerance(),
                props.sweepStarts(),
                props.escortCost(),
                props.defaultCabCapacity(),
                props.defaultMaxRideMinutes(),
                props.maxRidersPerPlan());
    }
}
