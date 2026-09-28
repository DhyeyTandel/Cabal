-- Standing time-of-day preferences an employee may set: do not pick up before
-- earliest_pickup (used on PICKUP plans), do not drop after latest_drop (used on DROP
-- plans). Both are nullable; nothing set means no preference.
alter table employees add column earliest_pickup time;
alter table employees add column latest_drop time;

-- Whether a stop's window (if any) could not be honoured. Only ever true for a stop
-- riding alone in its cab, which is always seated even when its window cannot be met.
alter table route_stops add column window_missed boolean not null default false;
