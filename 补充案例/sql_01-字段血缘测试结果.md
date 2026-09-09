# sql_01 字段血缘测试结果

## 测试 SQL

```sql
INSERT INTO whc.c
SELECT whc.a.field1, whc.b.*
FROM whc.a LEFT JOIN whc.b ON whc.a.id = whc.b.id;
```

## 初始化元数据

- `whc.a(id, field1)`
- `whc.b(id, name, value)`
- `whc.c(field1, id, name, value)`

## 字段血缘

| 目标字段 | 来源字段 | 类型 |
| --- | --- | --- |
| `whc.c.field1` | `whc.a.field1` | `DIRECT` |
| `whc.c.id` | `whc.b.id` | `DIRECT` |
| `whc.c.name` | `whc.b.name` | `DIRECT` |
| `whc.c.value` | `whc.b.value` | `DIRECT` |

## Join 血缘

- 表达式：`whc.a.id = whc.b.id`
- 关联表：`whc.a`、`whc.b`
- 关联字段：`whc.a.id`、`whc.b.id`

## 执行状态

- 解析状态码：`0`（成功）
- 告警数：`0`
- 自动化验收测试：`HiveQualifiedStarDdlScenarioTest`
