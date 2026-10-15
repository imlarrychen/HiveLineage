-- 场景：CREATE VIEW 后 ALTER VIEW 替换定义，再建第二层视图；下游必须使用新定义。
create view dim.statement_user_view as
select u.id, u.name from ods.users u;

alter view dim.statement_user_view as
select a.id, upper(a.name) as name from ods.users_archive a;

create view dim.statement_outer_view as
select x.id, x.name from (select v.id, v.name from dim.statement_user_view v) x;

insert overwrite table dwd.result(id, name)
select v.id, v.name from dim.statement_outer_view v;
