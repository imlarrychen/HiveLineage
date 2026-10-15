insert into dwd.t(a,b) select sum(1) over(partition by id order by dt),row_number() over(order by amount) from ods.t;
