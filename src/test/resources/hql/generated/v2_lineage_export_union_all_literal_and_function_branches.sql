insert into dwd.t(a,b) select 'A',upper('a') from ods.t union all select 'B',upper('b') from ods.u;
