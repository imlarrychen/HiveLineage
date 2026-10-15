-- OUTER posexplode returns multiple generated columns with the same physical input.
INSERT INTO TABLE dwd.result (id, rn, tag)
SELECT u.id, p.pos, p.tag
FROM (SELECT id, tags FROM ods.users) u
LATERAL VIEW OUTER posexplode(u.tags) p AS pos, tag;
