# nextentity-tests-legacy

2.2.3 `integration` 用例的移植载体：**用上一个版本的语义断言，验证当前实现**。

这个模块不是新代码的风格样板，也不是给人抄的示例。它存在的唯一理由是：3.x 自研的 65 条一致性用例
覆盖面太窄，而 2.2.3 那套 1,000+ 条用例在条件、排序、分页、锁、嵌入、批量写入这些**仍然存在**的能力上
积累了两年。把它们搬过来跑，比重新想一遍测什么更可靠。

## 怎么跑

```bash
# 默认只有 H2（内存库，无容器），随全量构建一起跑
./mvnw -pl nextentity-tests-legacy test

# 加上真 MySQL：需要 Docker 可达
./mvnw -pl nextentity-tests-legacy test -Ddbc=h2,mysql

# Docker 不可达时，指向一个已在跑的 MySQL（库名任意，用例自己建表）
NEXTENTITY_LEGACY_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/testdb?useSSL=false&allowPublicKeyRetrieval=true' \
NEXTENTITY_LEGACY_MYSQL_USER=root \
NEXTENTITY_LEGACY_MYSQL_PASSWORD=test \
./mvnw -pl nextentity-tests-legacy test -Ddbc=mysql
```

`-Ddbc=` 取值只有 `h2` / `mysql`，默认 `h2`。写了没接的环境名会直接报错，不会静默忽略。

外部实例可以这样起：

```bash
docker run -d --name nextentity-legacy-mysql \
  -e MYSQL_ROOT_PASSWORD=test -e MYSQL_DATABASE=testdb \
  -p 13306:3306 mysql:8.0.18
```

用例数是**按环境数乘出来的**：`-Ddbc=h2,mysql` 会跑两遍，1,186 条 = 593 条 × 2。

## 装置

`config/LegacyHarness` 一套真实 Hibernate（不用 Spring）：

- **建表用环境自带的 DDL**，不用 `hbm2ddl`。列名 `department_id` / `created_at` 依赖驼峰转下划线命名策略，
  所以显式装了 `CamelCaseToUnderscoresNamingStrategy`。这样实体映射写错会直接报「列不存在」，
  而不是被自动建表掩盖掉。
- **每次交付上下文前先复位**（清表 + 重装夹具）。2.2.3 靠用例自己写 `@AfterEach reset()`，漏写就污染后面的用例；
  这里把隔离从用例作者手里拿走。
- **写方法自动包事务**（已有事务则加入）。2.2.3 靠 `TransactionUpdateExecutor` 兜这一层，用例里大量
  `insert/update` 并没有自己开事务。

## 与 2.2.3 的差异

用例断言的**期望值基本没动**，动的是「从哪拿查询、用哪个算子名」，以及一批能力缺口。逐条记录在
[`porting/`](porting/)：

| 文件 | 批次 |
|---|---|
| [`porting/NOT-PORTED-port-a.md`](porting/NOT-PORTED-port-a.md) | 谓词 / 条件 / 聚合 / 关联 |
| [`porting/NOT-PORTED-port-b.md`](porting/NOT-PORTED-port-b.md) | 投影 / select / 分组 / fast 包 |
| [`porting/NOT-PORTED-port-c.md`](porting/NOT-PORTED-port-c.md) | 排序 / 字符串 / 数值 / 分页 |
| [`porting/NOT-PORTED-port-d.md`](porting/NOT-PORTED-port-d.md) | 写入 / 锁 / 事务 / 杂项 |
| [`porting/REPORT.md`](porting/REPORT.md) | 总数、裁决结论、待办 |

## 为什么本模块跳过 spotless / checkstyle / PMD

源文件刻意保持与上游一致的排版，以便和 `D:\source\java\nextentity` 逐文件 diff 复核，也便于将来
上游再改时重新同步。对移植语料做风格整形会让这个 diff 失去意义。所以三个插件在本模块的 `pom.xml` 里
显式 `skip`——这是**范围例外**，不是放宽标准：新写的代码仍然受全量门禁约束。
