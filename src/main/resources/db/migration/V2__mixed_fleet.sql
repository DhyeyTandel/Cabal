-- Plans used to have one cab capacity. They now carry a fleet: vehicle types, each
-- with seats and an optional limit on how many are available.
create table plan_vehicle_types (
    plan_id   bigint      not null references route_plans (id) on delete cascade,
    name      varchar(40) not null,
    seats     integer     not null,
    available integer,
    primary key (plan_id, name)
);

-- Existing plans become a fleet of one unlimited type with the old capacity.
insert into plan_vehicle_types (plan_id, name, seats, available)
select id, 'CAB', cab_capacity, null from route_plans;

alter table cab_routes add column vehicle_type varchar(40);
alter table cab_routes add column seats integer;
update cab_routes
set vehicle_type = 'CAB',
    seats        = (select p.cab_capacity from route_plans p where p.id = cab_routes.plan_id);
alter table cab_routes alter column vehicle_type set not null;
alter table cab_routes alter column seats set not null;

alter table route_plans drop column cab_capacity;
