-- Hive represents INTERVAL using an internal function; its opaque semantics stay explicit.
INSERT INTO dwd.result(tag, dt)
SELECT INTERVAL '1' DAY, INTERVAL '1-2' YEAR TO MONTH FROM ods.users;
