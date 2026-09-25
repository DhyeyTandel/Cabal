package dev.dhyey.cabrouter.config;

import dev.dhyey.cabrouter.routing.HaversineTravelModel;
import dev.dhyey.cabrouter.routing.RoutePlanner;
import dev.dhyey.cabrouter.routing.TravelModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RoutingProperties.class)
public class RoutingConfig {

    @Bean
    TravelModel travelModel(RoutingProperties props) {
        return new HaversineTravelModel(props.circuityFactor(), props.averageSpeedKmph());
    }

    @Bean
    RoutePlanner routePlanner(TravelModel travelModel) {
        return new RoutePlanner(travelModel);
    }
}
