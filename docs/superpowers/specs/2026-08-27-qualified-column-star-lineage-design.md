# 限定字段与星号血缘解析设计

## 背景与目标

补充案例中的 Hive SQL 同时使用了三段式字段引用和限定星号：

```sql
INSERT INTO whc.c
SELECT whc.a.field1, whc.b.*
FROM whc.a LEFT JOIN whc.b ON whc.a.id = whc.b.id;
```

当前实现只能稳定处理裸字段、二段式字段以及单表单独的 `SELECT *`。它不会展开混合投影中的
`owner.*`，也会错误拆分 `database.table.column`。本次目标是通用支持别名、表名、库表名限定的字段和星号，
并让投影血缘与 Join 条件使用一致的物理表解析规则。

## 支持范围

字段引用支持以下形式：

- `column`
- `alias.column`
- `table.column`
- `database.table.column`

星号投影支持以下形式，并允许与普通投影混合：

- `*`
- `alias.*`
- `table.*`
- `database.table.*`

Join 条件支持相同的字段限定形式。实现不针对 `whc` 或具体表名做特判。

## 方案

### 统一限定引用解析

新增内部限定引用模型或等价的内部解析结果，将表达式从右向左拆分为：

- 字段名或 `*`；
- owner，可能是别名、表名或 `database.table`。

owner 通过当前查询块的来源别名和输入对象解析为物理表。解析时优先匹配完整限定名，其次匹配显式别名和简单表名；
存在歧义时不猜测来源。

投影字段提取与 Join 字段提取复用相同的 owner 解析规则。最终 `ColumnRef` 保留物理限定名，例如
`whc.a.field1`，原始 lineage expression 保持用户 SQL 中的写法。

### 限定星号展开

星号展开由目标字段解析职责负责，并在目标列对齐之前执行：

1. 普通 SELECT 项原样保留。
2. 对每个 `owner.*`，解析 owner 对应的唯一输入表。
3. 通过现有 `metadataProvider` 和 `metadataCache` 加载该表字段。
4. 按元数据字段顺序生成 `physicalTable.column` 表达式。
5. 将展开后的表达式插入星号原位置，保持整体 SELECT 顺序。

裸 `*` 的现有单表行为保持不变。多表裸 `*` 因来源不唯一，不在本次自动猜测；限定星号无法解析或元数据缺失时，
保留原表达式并添加 warning，后续列数校验仍按现有规则处理。

### 当前案例的数据流

DDL 元数据初始化为：

- `whc.a(id, field1)`
- `whc.b(id, name, value)`
- `whc.c(field1, id, name, value)`

投影列表先从：

```text
whc.a.field1, whc.b.*
```

展开为：

```text
whc.a.field1, whc.b.id, whc.b.name, whc.b.value
```

再按目标表字段顺序形成：

| 目标字段 | 来源字段 | 类型 |
| --- | --- | --- |
| `whc.c.field1` | `whc.a.field1` | `DIRECT` |
| `whc.c.id` | `whc.b.id` | `DIRECT` |
| `whc.c.name` | `whc.b.name` | `DIRECT` |
| `whc.c.value` | `whc.b.value` | `DIRECT` |

Join 条件解析为 `whc.a.id = whc.b.id`，Join 字段为 `whc.a.id` 和 `whc.b.id`。

## 错误处理

- owner 无法匹配输入表：不选择默认首表，添加 `UNKNOWN_COLUMN_LINEAGE` warning。
- owner 匹配多个输入表：视为歧义，不猜测来源并添加 warning。
- 星号对应表缺少元数据：保留星号表达式并使用现有元数据缺失 warning。
- 星号展开后与目标列数不一致：继续使用现有 `COLUMN_COUNT_MISMATCH` 失败结果。

## 测试策略

先增加当前案例的失败测试，确认修复前只能生成两条且包含错误来源。实现后要求：

- 当前三段式混合投影生成四条正确字段血缘。
- `alias.column` 与 `alias.*` 正确解析。
- `table.column` 与 `table.*` 正确解析。
- `database.table.column` 与 `database.table.*` 正确解析。
- 限定星号在多个 SELECT 项中保持展开位置和字段顺序。
- Join 条件中的二段式、三段式字段均解析为正确物理表字段。
- 缺少元数据或 owner 歧义时产生 warning，不静默绑定默认表。
- 原有单表 `SELECT *` 测试保持通过。
- 完整 Maven 测试为 0 failures、0 errors。

## 约束与非目标

- 保持 Java 8 兼容。
- 不新增第三方依赖，不修改公开 API。
- 不修改用户的 `release/` 和 `补充案例/` 文件。
- 本次不重写 Hive AST 适配器，也不扩展多表裸 `SELECT *` 的猜测规则。
