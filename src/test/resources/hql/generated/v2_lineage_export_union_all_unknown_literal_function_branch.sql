insert into dwd.t(a) select 'A' from ods.t union all select company_mask('B') from ods.u;
