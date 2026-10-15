-- 场景：共享两层嵌套 UNION FROM 的两个 INSERT 分支，分别保存目标、写入模式和字段映射。
from (
    select combined.id, combined.name, combined.amount
    from (
        select u.id, u.name, u.amount from ods.users u
        union all
        select a.id, a.name, a.amount from ods.users_archive a
    ) combined
) shared
insert into dwd.result(id, name) select shared.id, shared.name
insert overwrite table dwd.result_archive(id, amount) select shared.id, shared.amount;
