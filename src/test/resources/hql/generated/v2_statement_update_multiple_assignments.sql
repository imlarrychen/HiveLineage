-- 场景：UPDATE 的每个 SET 产生独立字段赋值，保留更新前自身字段依赖和 WHERE 条件。
update dwd.result set amount=amount+1, name=upper(name) where id > 0;
