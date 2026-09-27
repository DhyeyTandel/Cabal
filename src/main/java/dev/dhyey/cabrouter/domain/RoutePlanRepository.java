package dev.dhyey.cabrouter.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutePlanRepository extends JpaRepository<RoutePlan, Long> {

    /** Newest first, for the website's plan list. */
    List<RoutePlan> findTop50ByOrderByIdDesc();
}
