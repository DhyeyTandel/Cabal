-- Which travel model timed each cab: HAVERSINE, OSRM or HAVERSINE_FALLBACK.
-- Cabs routed before this migration were all timed by haversine.
alter table cab_routes add column travel_source varchar(20) not null default 'HAVERSINE';
