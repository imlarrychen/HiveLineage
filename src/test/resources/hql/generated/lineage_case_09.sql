-- 覆盖点：窗口函数 + case when + cast + 字符串拼接/concat + 未知 UDF 降级
-- 预期：所有复杂表达式均应尽量提取 sourceColumns 和 expression；lineageType 为 DERIVED。
insert overwrite table dwd.user_feature_wide
select
       user_id
      ,case when status = 1 then score else 0 end as valid_score
      ,row_number() over(partition by user_id order by update_time desc) as rn
      ,concat(cast(user_id as string), '_', dt) as user_day_key
      ,my_udf(score, level_code) as udf_score
from ods.user_feature
where dt = '${bizdate}';
