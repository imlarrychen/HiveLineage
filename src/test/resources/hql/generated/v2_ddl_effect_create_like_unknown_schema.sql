-- CREATE LIKE preserves unknown schema until cloning is explicitly implemented.
create table dwd.ddl_like_known_source like ods.users;
insert overwrite table dwd.result(id) select * from dwd.ddl_like_known_source;
create external table dwd.ddl_like_unknown_source like ods.ddl_missing_source location '/warehouse/ddl/like';
insert overwrite table dwd.result(id) select * from dwd.ddl_like_unknown_source;
