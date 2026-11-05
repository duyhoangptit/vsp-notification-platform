-- Monthly partitions for history and events (R9). The base date is always passed by the application
-- (computed from the Clock bean, R29); only this migration's bootstrap call uses the DB date.
CREATE OR REPLACE FUNCTION pnh_ensure_month_partitions(base_date date, months_ahead int)
RETURNS int LANGUAGE plpgsql AS $$
DECLARE
    m          int;
    start_d    date;
    end_d      date;
    created    int := 0;
    tbl        text;
    part_name  text;
BEGIN
    IF months_ahead < 0 OR months_ahead > 36 THEN
        RAISE EXCEPTION 'months_ahead out of range: %', months_ahead;
    END IF;
    FOR m IN 0..months_ahead LOOP
        start_d := (date_trunc('month', base_date) + make_interval(months => m))::date;
        end_d   := (start_d + interval '1 month')::date;
        FOREACH tbl IN ARRAY ARRAY['pnh_notifications_history', 'pnh_notification_events'] LOOP
            part_name := format('%s_y%sm%s', tbl, to_char(start_d, 'YYYY'), to_char(start_d, 'MM'));
            IF to_regclass(part_name) IS NULL THEN
                EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                               part_name, tbl, start_d::timestamptz, end_d::timestamptz);
                created := created + 1;
            END IF;
        END LOOP;
    END LOOP;
    RETURN created;
END;
$$;

-- bootstrap: current month + next 2 (the PartitionMaintenanceJob keeps 3 months ahead from the Clock bean)
SELECT pnh_ensure_month_partitions(CURRENT_DATE, 2);
