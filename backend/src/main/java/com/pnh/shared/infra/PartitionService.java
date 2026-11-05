package com.pnh.shared.infra;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Calls {@code pnh_ensure_month_partitions(base_date, months_ahead)}; the base date always comes from Java (R29). */
@Component
public class PartitionService {

    private final JdbcClient jdbc;

    public PartitionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return number of partitions created */
    public int ensureMonths(Instant around, int monthsAhead) {
        LocalDate base = around.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        Integer created = jdbc.sql("SELECT pnh_ensure_month_partitions(:base, :ahead)")
                .param("base", Date.valueOf(base))
                .param("ahead", monthsAhead)
                .query(Integer.class)
                .single();
        return created == null ? 0 : created;
    }
}
