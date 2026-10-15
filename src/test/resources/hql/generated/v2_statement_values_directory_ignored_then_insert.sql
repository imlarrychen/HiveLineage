-- 场景：VALUES 与 DIRECTORY/LOCAL DIRECTORY 只做语法审计，均不产生正式表/列血缘；后续 INSERT 正常执行。
insert into dwd.result(id, name) values(1, 'one'), (2, 'two');
insert overwrite directory '/tmp/v2_statement_export' select u.id, u.name from ods.users u;
insert overwrite local directory '/tmp/v2_statement_local_export' select u.id from ods.users u;

insert overwrite table dwd.result(id, name)
select u.id, u.name from ods.users u;
