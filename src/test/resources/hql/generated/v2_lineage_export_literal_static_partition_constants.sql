insert into dwd.t partition(dt='20261015') (id,name,amount) select 42,'fixed',null from ods.t;
