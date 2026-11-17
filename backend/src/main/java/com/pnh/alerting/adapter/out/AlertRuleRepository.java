package com.pnh.alerting.adapter.out;

import com.pnh.alerting.domain.AlertRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRuleRepository extends JpaRepository<AlertRule, String> {
    List<AlertRule> findAllByOrderByRuleCodeAsc();
}
