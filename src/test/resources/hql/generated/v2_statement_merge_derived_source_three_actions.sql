-- 场景：MERGE 来源是嵌套查询；matched UPDATE/DELETE 与 not matched INSERT 保留三个贡献和条件。
merge into dwd.result t
using (
    select n.id, n.name, n.amount, n.dt
    from (select u.id, upper(u.name) as name, u.amount, u.dt from ods.users u) n
) s on t.id=s.id
when matched and s.amount > 0 then update set amount=s.amount, name=s.name
when matched and s.amount=0 then delete
when not matched then insert values(s.id, s.name, s.amount, null, null, 0, s.dt);
