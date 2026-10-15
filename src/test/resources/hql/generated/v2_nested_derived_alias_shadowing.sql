-- 场景：内外查询都使用别名 x，内层归属 archive，外层归属 users。
insert overwrite table dwd.result(id, name)
select x.id, archive_row.name
from ods.users x
join (
    select x.id, x.name
    from ods.users_archive x
    where x.amount > 0
) archive_row on x.id = archive_row.id;
