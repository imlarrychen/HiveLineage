-- 场景：DELETE 保留目标行集和 WHERE 条件，不伪造被删除字段的赋值映射。
delete from dwd.result where amount=0 and dt='20261014';
