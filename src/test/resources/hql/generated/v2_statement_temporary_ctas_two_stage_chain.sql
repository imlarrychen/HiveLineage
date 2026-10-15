-- 场景：两个 temporary CTAS 串联，默认不发布临时血缘，最终字段仍展开到原表。
create temporary table statement_tmp_users as
select u.id, u.name, u.amount from ods.users u;

create temporary table statement_tmp_enriched as
select t.id, upper(t.name) as name, t.amount + 1 as amount
from statement_tmp_users t;

insert overwrite table dwd.result(id, name, amount)
select e.id, e.name, e.amount from statement_tmp_enriched e;
