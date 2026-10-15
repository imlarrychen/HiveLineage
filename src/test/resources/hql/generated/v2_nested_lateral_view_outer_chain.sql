-- 场景：派生表中的 LATERAL VIEW OUTER 再包一层投影，生成字段回溯 tags 参数。
insert overwrite table dwd.result(id, tag)
select filtered_tags.id, filtered_tags.tag
from (
    select expanded.id, expanded.tag
    from (
        select u.id, generated.tag
        from ods.users u
        lateral view outer explode(u.tags) generated as tag
    ) expanded
    where expanded.tag is not null
) filtered_tags;
