insert into dwd.t(a,b,c,d,e) select 1+2,upper('x'),cast(1 as string),case when true then 1 else 2 end,concat('a','b') from ods.t;
