-- 场景：静态分区由常量赋值，显式业务字段映射只消耗 SELECT 的业务输出。
insert overwrite table dwd.result partition(dt='20261014') (id, name)
select u.id, u.name from ods.users u;
