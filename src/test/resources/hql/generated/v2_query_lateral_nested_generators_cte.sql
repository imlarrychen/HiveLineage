-- A second generator consumes the first generator output through a CTE query.
WITH tagged AS (
    SELECT u.id, t.tag FROM ods.users u
    LATERAL VIEW explode(u.tags) t AS tag
)
INSERT INTO TABLE dwd.result (id, tag)
SELECT q.id, p.part FROM tagged q
LATERAL VIEW OUTER explode(split(q.tag, ':')) p AS part;
