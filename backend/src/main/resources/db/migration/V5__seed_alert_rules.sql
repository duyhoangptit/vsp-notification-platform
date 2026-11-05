-- Default incident alert rules (data-model.md, R24). Console edits values only.
INSERT INTO pnh_alert_rules (rule_code, enabled, threshold, window_minutes, min_sample, cooldown_minutes,
                             created_by, updated_by)
VALUES ('STUCK_DELIVERY_SPIKE', TRUE, 10, 10, NULL, 30, 'system', 'system'),
       ('PROVIDER_UNAVAILABLE', TRUE, 2, 2, NULL, 30, 'system', 'system'),
       ('CHANNEL_FAILURE_RATE', TRUE, 30, 10, 50, 30, 'system', 'system'),
       ('INGEST_REJECTED_SPIKE', TRUE, 20, 10, NULL, 30, 'system', 'system');
