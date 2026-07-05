-- 覆盖点：union 非 union all、复杂类型字段和复杂表达式。
-- 预期：plain union 两个分支都进入字段来源；tags[0]、concat/size 等复杂表达式尽量提取来源字段。
insert overwrite table dwd.union_distinct_complex_target(user_id, label, tag_head, tag_count)
select
       user_id
      ,concat('base:', user_name) as label
      ,tags[0] as tag_head
      ,size(tags) as tag_count
from ods.user_base
where dt = '${bizdate}'
union
select
       user_id
      ,concat('feature:', cast(score as string)) as label
      ,level_code as tag_head
      ,cast(score as int) as tag_count
from ods.user_feature
where dt = '${bizdate}';
