create view dim.v as select id,upper('fixed') name,'literal' dt from ods.t;
insert into dwd.t(a,b,c) select id,name,dt from dim.v;
