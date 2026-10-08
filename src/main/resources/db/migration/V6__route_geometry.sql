-- Road geometry for each cab's route, so the map follows streets instead of straight lines.
-- One polyline6-encoded leg per line (newline-joined), in driving order; null when unknown
-- (haversine plans, an OSRM outage when the cab was written, or cabs stored before this).
alter table cab_routes add column route_legs text;
