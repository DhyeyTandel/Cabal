-- Vehicle types get prices: a fixed charge per trip and a charge per km. Existing
-- fleets get the defaults, which reproduce the old "fewest vehicles, then fewest km"
-- behaviour.
alter table plan_vehicle_types add column cost_per_trip double precision not null default 1000;
alter table plan_vehicle_types add column cost_per_km double precision not null default 15;

-- Each cab records what it costs. Guard costs were not tracked when older plans were
-- made, so their backfilled cost covers the vehicle only.
alter table cab_routes add column cost double precision not null default 0;
update cab_routes
set cost = (select v.cost_per_trip + v.cost_per_km * cab_routes.distance_km
            from plan_vehicle_types v
            where v.plan_id = cab_routes.plan_id and v.name = cab_routes.vehicle_type);
