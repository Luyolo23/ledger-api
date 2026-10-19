INSERT INTO users (id, email, password_hash, created_at)
VALUES (
           '00000000-0000-0000-0000-000000000001',
           'system@internal.local',
           '!disabled-account!',
           now()
       );

INSERT INTO accounts (id, user_id, name, currency, created_at)
VALUES (
           '00000000-0000-0000-0000-000000000002',
           '00000000-0000-0000-0000-000000000001',
           'System Equity ZAR',
           'ZAR',
           now()
       );