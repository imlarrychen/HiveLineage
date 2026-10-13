with x as (
  select a.id as id
  from ods.a a
  join ods.b b
    on a.id = b.id
),
y as (
  select c.id as id
  from ods.c c
  join ods.d d
    on c.id = d.id
)
select *
from x
join y
  on x.id = y.id
