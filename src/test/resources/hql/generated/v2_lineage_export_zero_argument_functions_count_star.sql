insert into dwd.t(a,b,c) select current_date(),current_timestamp(),count(*) from ods.t;
