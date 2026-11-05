-- LOCAL PROFILE ONLY (spring.flyway.locations in application-local.yml). Dev credentials, never for production.
-- Secrets of channel configs are filled in encrypted form by LocalChannelSecretsSeeder (profile local).

INSERT INTO pnh_clients (client_id, client_name, client_secret_hash, rate_limit_per_min, scopes, allow_direct_content,
                         status, created_by, updated_by)
VALUES ('demo-client', 'Demo client', '$2y$10$jaB7fmcOfr514F0VEzji/.bSeVCFiP2EnO.u3y9HffkUCl5vo1xSm', 1000,
        'SEND,TRACK,USER_DELEGATE', TRUE, 'ACTIVE', 'system', 'system')
ON CONFLICT (client_id) DO NOTHING;

INSERT INTO pnh_operators (username, display_name, password_hash, role, status, created_by, updated_by)
VALUES ('admin', 'Local Admin', '$2y$10$7OLFfh0/7guJI2HfEpwdkOSPvka2iNUNFoT/zHx3uFCMp1db/arHS', 'ADMIN', 'ACTIVE', 'system', 'system'),
       ('editor', 'Template Editor', '$2y$10$kclLHuPhcMqiAfPQhAELO.5t7W/x4NMC9BZP3stJczxkABcdXuwQy', 'TEMPLATE_EDITOR', 'ACTIVE', 'system', 'system'),
       ('support', 'Support Viewer', '$2y$10$9c.YvQKnNuEPNtUapKBiYeGOvamApDjRVldIdSWDDeR6epKmQapzK', 'SUPPORT_VIEWER', 'ACTIVE', 'system', 'system')
ON CONFLICT (username) DO NOTHING;

INSERT INTO pnh_templates (template_code, template_name, channel_type, category, is_critical, otp_variable, expiry_variable,
                           subject_template, title_template, body_template, required_variables, status, created_by, updated_by)
VALUES ('OTP_VERIFICATION', 'OTP verification (email)', 'EMAIL', 'OTP', FALSE, 'otp_code', 'expired_minutes',
        'Your verification code', NULL,
        '<p>Your verification code is <b>${otp_code}</b>. It is valid for ${expired_minutes} minutes.</p>',
        ARRAY['otp_code', 'expired_minutes'], 'ACTIVE', 'system', 'system'),
       ('OTP_SMS', 'OTP verification (SMS)', 'SMS', 'OTP', FALSE, 'otp_code', 'expired_minutes',
        NULL, NULL, 'Ma OTP cua ban la ${otp_code}, hieu luc ${expired_minutes} phut.',
        ARRAY['otp_code', 'expired_minutes'], 'ACTIVE', 'system', 'system'),
       ('ORDER_SHIPPED_PUSH', 'Order shipped (push)', 'PUSH', 'STANDARD', FALSE, NULL, NULL,
        NULL, 'Order ${order_id} shipped', 'Your order ${order_id} is on the way.',
        ARRAY['order_id'], 'ACTIVE', 'system', 'system'),
       ('NEWSLETTER_EMAIL', 'Newsletter', 'EMAIL', 'STANDARD', FALSE, NULL, NULL,
        'Hello ${name}', NULL, '<p>Hi ${name}, here is our latest newsletter.</p>',
        ARRAY['name'], 'ACTIVE', 'system', 'system')
ON CONFLICT (template_code) DO NOTHING;

INSERT INTO pnh_channel_configs (channel_type, provider_key, is_active, settings, created_by, updated_by)
VALUES ('EMAIL', 'SMTP', TRUE, '{"host":"localhost","port":1025,"tls":false,"from":"PNH <no-reply@pnh.local>"}', 'system', 'system'),
       ('SMS', 'GENERIC_HTTP_SMS', TRUE, '{"endpoint":"http://localhost:8089/sms/send","sender":"PNH"}', 'system', 'system'),
       ('PUSH', 'FCM', TRUE, '{"project_id":"pnh-local","base_url":"http://localhost:8089","token_uri":"http://localhost:8089/token"}', 'system', 'system')
ON CONFLICT (channel_type, provider_key) DO NOTHING;

INSERT INTO pnh_alert_recipients (email, display_name, is_active, created_by, updated_by)
VALUES ('ops@example.com', 'Ops team', TRUE, 'system', 'system')
ON CONFLICT (email) DO NOTHING;
