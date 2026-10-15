with renamed as (select id as renamed_id,upper('fixed') as text_value from ods.t) insert into dwd.t(a,b) select q.renamed_id,q.text_value from (select * from renamed) q;
