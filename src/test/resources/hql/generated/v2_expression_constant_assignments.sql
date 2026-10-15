-- Literal outputs retain assignments without creating source column edges.
INSERT INTO dwd.result(id, name, dt)
SELECT 42, 'fixed', NULL FROM ods.users;
