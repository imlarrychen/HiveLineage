create table tmp.copy_source as select id from ods.t; insert into dwd.t(a) select id from tmp.copy_source;
