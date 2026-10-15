-- 场景：INSERT INTO 和 OVERWRITE 的显式目标列按声明顺序映射，而不是目标元数据顺序。
insert into dwd.result(name, id, amount)
select u.name, u.id, u.amount from ods.users u;

insert overwrite table dwd.result_archive(amount, name, id)
select u.amount + 1, upper(u.name), u.id from ods.users_archive u;
