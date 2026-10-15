-- Recursive definition without WITH RECURSIVE is rejected by the semantic binder.
WITH cyclic AS (SELECT id FROM cyclic)
INSERT INTO dwd.result(id) SELECT id FROM cyclic;
