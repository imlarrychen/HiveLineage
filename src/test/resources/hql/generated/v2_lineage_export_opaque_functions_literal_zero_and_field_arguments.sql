insert into dwd.t(a,b,c) select company_mask('fixed'),company_clock(),company_mask(name) from ods.t;
