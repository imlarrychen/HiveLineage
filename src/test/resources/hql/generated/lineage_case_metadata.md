# Generated Hive Lineage SQL Test Metadata

这份文件配合 `lineage_case_01.sql` 到 `lineage_case_17.sql` 使用。SQL 文件本身不依赖真实 MySQL 必须在线；自动化测试可以用 mock `HiveMetadataProvider` 加载下面的字段清单。若要走 MySQL，可按“推荐元数据清单”插入 `metadata_column_v2`，并按“视图元数据插入建议”插入 `hive_view_parse_info`。

## 覆盖矩阵

| 文件 | 覆盖点 |
| --- | --- |
| `lineage_case_01.sql` | 生产风格 set、普通建表 DDL 忽略、insert overwrite select、left join、多表字段归属、函数表达式 |
| `lineage_case_02.sql` | 动态分区配置、union all、distinct、多来源表字段对齐、常量字段 |
| `lineage_case_03.sql` | `${}`、`${hiveconf:}`、`${hivevar:}`、`@{}` 变量处理、insert into、算术表达式、nvl 函数 |
| `lineage_case_04.sql` | `select *` 元数据展开、未显式目标字段对齐 |
| `lineage_case_05.sql` | CTAS、CTE、聚合函数、group by、`count(*)` |
| `lineage_case_06.sql` | create temporary table as select、临时表血缘串联、case when |
| `lineage_case_07.sql` | create view as select、alter view as select、视图作为输入表、视图展开 |
| `lineage_case_08.sql` | 静态分区、动态分区、lateral view explode、explode 输出别名回溯 |
| `lineage_case_09.sql` | 窗口函数、case when、cast、concat、未知 UDF 降级 |
| `lineage_case_10.sql` | use、普通 DDL、纯 select、insert values、insert overwrite directory 默认忽略、最终可解析 insert |
| `lineage_case_11.sql` | 单条解析失败返回失败结果、同脚本后续 insert 继续解析 |
| `lineage_case_12.sql` | 目标元数据缺失降级、表血缘保留、`METADATA_NOT_FOUND` warning |
| `lineage_case_13.sql` | create temporary table 与同名 metastore 视图冲突时，临时表血缘优先 |
| `lineage_case_14.sql` | inline FROM 子查询、聚合字段别名回溯 |
| `lineage_case_15.sql` | where exists 子查询输入表保留 |
| `lineage_case_16.sql` | left semi join、full outer join |
| `lineage_case_17.sql` | plain union、复杂类型字段访问、concat/size/cast 复杂表达式 |

## 推荐元数据清单

### default

| 表 | 字段 |
| --- | --- |
| `default.test_b` | `b1`, `b2`, `b3`, `dt`, `env` |
| `default.test_a` | `a1`, `a2`, `ds`, `env_name` |
| `default.tmp_shadow_user` | `user_id`, `user_name`；作为 VIEW 元数据，用于验证同名临时表优先 |

### ods

| 表 | 字段 |
| --- | --- |
| `ods.user_base` | `user_id`, `user_name`, `dept_id`, `status`, `tags`, `dt` |
| `ods.user_dept` | `dept_id`, `dept_name` |
| `ods.order_detail` | `user_id`, `order_id`, `amount`, `pay_time`, `pay_status`, `dt` |
| `ods.user_feature` | `user_id`, `status`, `score`, `update_time`, `dt`, `level_code` |
| `ods.union_source_a` | `id`, `name` |
| `ods.union_source_b` | `id`, `name` |

### dwd

| 表 | 字段 |
| --- | --- |
| `dwd.user_base_snapshot` | `user_id`, `id`, `name`, `user_name`, `first_name`, `last_name`, `dept_id`, `dt`, `tags`, `amount`, `status` |
| `dwd.user_order_summary` | `user_id`, `total_amount`, `order_cnt`, `last_pay_time` |
| `dwd.user_profile_enriched` | `user_id`, `user_name`, `dept_name`, `user_status`, `process_date` |
| `dwd.active_user_profile` | `user_id`, `user_name`, `dept_name` |
| `dwd.user_tag_detail` | `user_id`, `tag`, `source_system`, `dt` |
| `dwd.user_feature_wide` | `user_id`, `valid_score`, `rn`, `user_day_key`, `udf_score` |
| `dwd.ignore_me` | `id`, `name` |
| `dwd.parse_error_target` | `user_id` |
| `dwd.parse_recovery_target` | `user_id`, `user_name` |
| `dwd.temp_shadow_target` | `user_id`, `user_name` |
| `dwd.inline_subquery_target` | `user_id`, `order_cnt` |
| `dwd.exists_filter_target` | `user_id`, `user_name` |
| `dwd.semi_join_target` | `user_id`, `user_name` |
| `dwd.full_outer_join_target` | `user_id`, `profile_name`, `feature_status` |
| `dwd.union_distinct_complex_target` | `user_id`, `label`, `tag_head`, `tag_count` |

有意缺失元数据：`dwd.metadata_missing_target` 不应加入 mock metastore 或 MySQL 测试数据，用于验证目标元数据缺失时仍保留表血缘并输出 warning。

### dim

| 表/视图 | 类型 | 字段 | 视图定义建议 |
| --- | --- | --- | --- |
| `dim.v_user_profile` | VIEW | `user_id`, `user_name`, `dept_name` | `select u.user_id, upper(u.user_name) as user_name, d.dept_name from ods.user_base u left join ods.user_dept d on u.dept_id = d.dept_id where u.status = 1` |
| `default.tmp_shadow_user` | VIEW | `user_id`, `user_name` | `select id as user_id, name as user_name from ods.union_source_a` |

### etl_monitor

| 表 | 字段 |
| --- | --- |
| `etl_monitor.bdp_meta_leviathan_t_account_login_record` | `domain`, `user_id`, `login_time`, `os_type`, `ip`, `province`, `city`, `mobile_id` |
| `etl_monitor.meta_mid_bdp_usr_info` | `domain`, `bbk_name`, `dept_ful_nm`, `user_id`, `user_name`, `emp_name`, `role`, `dept_name` |
| `etl_monitor.meta_mid_bdp_usr_login_record` | `domain`, `bbk_name`, `dept_ful_nm`, `user_id`, `user_name`, `emp_name`, `login_time`, `os_type`, `ip`, `province`, `city`, `mobile_id`, `dw_upd_tm` |
| `etl_monitor.bdp_meta_mcmeta_online_share_dash` | `user_id`, `group_id`, `dsh_id`, `type` |
| `etl_monitor.bdp_meta_mcmeta_online_dashboard` | `dsh_id`, `proj_id`, `name`, `user_id`, `is_del` |
| `etl_monitor.bdp_meta_mcmeta_online_project` | `proj_id`, `name` |
| `etl_monitor.bdp_meta_leviathan_t_account` | `user_id`, `name` |
| `etl_monitor.meta_mid_bdp_grp_usr_map` | `domain`, `bbk_name`, `dept_ful_nm`, `user_id`, `user_name`, `emp_name`, `role`, `group_id` |
| `etl_monitor.meta_mid_bdp_user_dashboard_pms_group` | `domain`, `bbk_name`, `dept_ful_nm`, `user_id`, `user_name`, `emp_name`, `user_role`, `proj_id`, `proj_name`, `dsh_id`, `dsh_name`, `dsh_owner`, `dis_type`, `dw_upd_tm` |

## MySQL metadata_column_v2 插入建议

测试时只需要保证以下字段有值：

- `db_en_name`：库名，例如 `ods`
- `tb_en_name`：表名，例如 `user_base`
- `column_name`：字段名，例如 `user_id`
- `integer_idx`：字段顺序，从 1 开始
- `is_active`：有效标识，建议 `b'1'`

## 视图元数据插入建议

JDBC provider 会结合 `hive_view_parse_info` 判断视图并生成视图展开 SQL。测试时建议至少插入：

| 字段 | 建议值 |
| --- | --- |
| `view_name` | 视图全名，例如 `dim.v_user_profile`；provider 也兼容单独表名 `v_user_profile` |
| `view_source` | 视图上游来源，格式为 `库名.表名`，例如 `ods.user_base` |
| `failed_status` | `b'0'` |
| `view_tbl_id`、`view_db_id` | 测试可使用任意稳定 id |

如果一个视图解析出多个来源表，可以为同一个 `view_name` 插入多行不同 `view_source`。provider 会生成保守的 `union all` 形式以保留多来源表血缘；复杂视图的精确 join/filter 表达式仍建议来自 Hive Metastore 原始视图 SQL。

## 视图处理风险

- 当前解析器通过 `HiveTableMetadata.isView()` 判断对象是否为视图，判断来源是 `MetadataProvider`，不是 SQL 文本或命名规则。
- 当前 JDBC provider 会查询 `hive_view_parse_info.view_name/view_source` 判断视图，并基于 `view_source` 生成可解析的简单视图 SQL；表血缘保留视图节点并补充视图定义上游，字段血缘继续追溯到原表字段。
- 当前脚本级有 `viewColumnLineageCache`，同一脚本中重复读取同一视图可以复用字段映射；如果需要工作流级跨任务复用，需要把视图展开缓存外置到 workflow/global cache。
- 当前 `HiveMetadataProvider` 只有 `getTableMetadata(TableIdentifier)`，字段元数据和对象类型元数据没有拆成两个轻量接口；`TABLE_ONLY + expandViewLineage=true` 时也可能加载完整字段列表。
- `hive_view_parse_info.view_source` 可以支撑视图是否存在和上游表级补充；如果需要完全保留复杂视图字段表达式、join 条件和过滤条件，生产侧仍建议补充 Hive Metastore 原始视图 SQL。
- 嵌套视图、循环视图、视图定义缺失都应通过 warning 验证，不建议只验证 happy path。
