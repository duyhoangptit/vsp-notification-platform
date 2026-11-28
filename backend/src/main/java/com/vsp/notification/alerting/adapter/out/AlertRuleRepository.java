package com.vsp.notification.alerting.adapter.out;

import com.vsp.notification.alerting.domain.AlertRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRuleRepository extends JpaRepository<AlertRule, String> {
    List<AlertRule> findAllByOrderByRuleCodeAsc();
}
