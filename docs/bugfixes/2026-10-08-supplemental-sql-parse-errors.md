# 2026-10-08 补充 SQL 解析报错小结

- 案例来源：`补充案例/sql解析问题20261008.txt`（4 条 SQL）。
- 关联修复：`30e0881`。

| 案例 | 原因 | 处理结果 |
| --- | --- | --- |
| 1：`sopr_tsk_dtl_lmt` | 主语句是 `INSERT INTO ... PARTITION`，`WHERE IN` 内又有 `SELECT`。旧代码递归查找 `TOK_DESTINATION`，误取子查询的临时结果节点，导致主目标表为 `null`。 | 目标节点只从主 `TOK_INSERT` 的直接子节点读取；原 SQL 可识别目标表。`DISTINCT` 不是本次报错原因。 |
| 2：`lc60_t_data_stat` | 与案例 1 相同：`WHERE IN` 子查询的临时 `TOK_DESTINATION` 抢先匹配，主 `TOK_INSERT_INTO` 被跳过。 | 同一修复；原 SQL 可识别目标表。 |
| 3：`acglgdthp` | `FROM (SELECT ...)` 派生表缺少别名，Hive Parser 在后面的 `INNER JOIN` 处报语法错误。 | 需修改 SQL：在右括号后加别名，例如 `) gl INNER JOIN ... ON PAM_COD = gl.gl_org_id`。该条不是解析器代码缺陷。 |
| 4：`xc_dept` | `INSERT INTO ... SELECT` 只有字面量和 UDF，没有 `FROM`。Hive AST 可解析，但血缘分析旧逻辑要求输入表非空，因而错误地报为不支持。 | 已允许无来源表的有效写入返回成功；输入表和表级血缘为空。五个字面量投影为 `CONSTANT`，UDF 投影为无来源列的 `DERIVED`。 |

原文件中的 `UNSUPPORTED_STATEMENT` 是笼统警告，不能据此认定四条 SQL 都是不支持的语法。案例 1、2、4 是分析器误判；案例 3 是 SQL 本身缺少派生表别名。

修复还把完整表依赖与主查询投影来源分开：`WHERE/EXISTS` 子查询仍计入表依赖，但不会被 `SELECT *` 当作主查询字段来源。回归测试覆盖主目标表、谓词子查询星号投影和无 `FROM` 的常量写入。合入后执行 `mvn test`，退出码为 0。
