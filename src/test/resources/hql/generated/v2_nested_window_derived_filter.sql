-- 场景：内层窗口函数，外层以窗口别名过滤；rn 依赖 partition/order 字段。
insert overwrite table dwd.result(id, amount, rn)
select ranked.id, ranked.amount, ranked.row_num
from (
    select u.id, u.amount,
           row_number() over(partition by u.dept_id order by u.amount desc, u.dt desc) as row_num
    from ods.users u
) ranked
where ranked.row_num <= 3;
