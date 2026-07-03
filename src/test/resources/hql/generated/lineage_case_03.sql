-- 覆盖点：session set 变量 + ${hiveconf:} / ${hivevar:} 替换 + @{env} 兜底 + insert into select + 算术/函数表达式
-- 预期：rawSql 保留变量原文，normalizedSql 替换 bizdate、VARI 和环境变量；字段 b1,b2 -> a1 为 DERIVED，b3 -> a2 为 DERIVED。
set bizdate = 20260701;
set hivevar:VARI = '20';

insert into test_a(a1, a2, ds, env_name)
select
       b1 + b2 as a1
      ,nvl(b3, ${hivevar:VARI}) as a2
      ,'${hiveconf:bizdate}' as ds
      ,'@{run_env}' as env_name
from test_b
where dt = '${bizdate}';
