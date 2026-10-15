-- 场景：混合分区；静态 dt 是常量，动态 tag 取最后一个输出并回溯数组下标表达式。
insert overwrite table dwd.result partition(dt='20261014', tag) (id, name)
select u.id, u.name, u.tags[0] from ods.users u;
