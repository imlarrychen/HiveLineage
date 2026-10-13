select *
from ods.a a
join ods.b b
  on a.id = b.id
where exists (
  select 1
  from ods.c c
  join ods.d d
    on c.id = d.id
  where c.id = a.id
)
