# CHANGELOG

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

破坏性变更必须在对应条目里写明「为什么必须破」与迁移方式（见 [AGENTS.md](AGENTS.md)「公开 API 是契约」）。

## [3.0.0-SNAPSHOT] — 未发布

### Added

- 仓库骨架：`api` / `core` / `jpa` / `spring` / `testing` / `tests-jpa` / `bom` 七个模块与依赖方向约束。
- **第二后端 `nextentity-jdbc`（纯 JDBC，内置 H2 / MySQL / PostgreSQL / SQL Server 四方言）**：`JdbcEntityOperationsFactory(DataSource)` 探测方言后装配 `JdbcMeta`（`jakarta.persistence` 注解反射，不带 Hibernate）/ `JdbcCompiler`（`QuerySpec → {sql, bindings}`）/ `JdbcExecutor` / `JdbcPersistExecutor`（生成键回填、`WHERE id=? [AND version=?]` 乐观锁）。`jdbc` 只依赖 `core`，与 `jpa` 平行。**方言是可实现的对外接缝**：`JdbcDialect` 公开 8 个成员（`name()` + `quote`/`selectHead`/`limitClause`/`lock`/`averageOf`/`likeEscape` 七个 default 钩子，其中 `quote` 负责标识符引用），未内置的数据库实现该接口后 `new JdbcEntityOperationsFactory(dataSource, customDialect)` 即可接入，无需改 `jdbc` 模块；`JdbcDialect.of(String)` 按产品名探测四种内置方言，未识别时抛 `UnsupportedOperationException` 并引导注入自定义实现。方言接缝与不变量见 `docs/DESIGN.md` §6.1，决策见 §7。
- **`nextentity-tests-jdbc`**：把 `nextentity-testing` 的 32 个一致性/差分/变形基类接到 JDBC 后端（31 个基类 × 三方言接线 = 93 个接线类，`MySql*`/`Postgres*`/`SqlServer*` 互斥命名），H2 走 surefire、真 MySQL（`MySql*`）、真 PostgreSQL（`-Pjdbc-postgres`，`Postgres*`）、真 SQL Server（`-Pjdbc-sqlserver`，`SqlServer*`）各走一条 failsafe 容器通道；`nextentity-testing` 一行未改，证明接缝没被 JPA 绑架。三条容器通道的报告目录分别为 `target/failsafe-reports/`、`target/failsafe-pg-reports/`、`target/failsafe-mssql-reports/`，凭证按目录认成 `failsafe:` / `failsafe-pg:` / `failsafe-mssql:` 三条独立通道。
- **保留字 / 存量数据库表·列名（JDBC 四方言 + JPA H2/MySQL，同一套方案）**：注解里的名字被**反引号包裹**即表示该标识符需要引用（`@Table`/`@Entity(name)`、`@Column(name)`、`@JoinColumn(name|referencedColumnName)` 通用）；未包裹仍是裸名（既有 SQL 逐字节不变）。反引号只是跨后端**标记**，引号字符由方言决定：JDBC 侧 H2/PostgreSQL `"…"`、MySQL 反引号、SQL Server `[…]`；写侧（INSERT/UPDATE）同样只对标记过的标识符引用。meta 归一化为裸名 + `quoted` 标志，所有名字比较与生成键回读用裸名。**JPA 侧零生产代码**：Hibernate 原生把反引号当引用标记，H2 上实测渲染 `"…"`、与 JDBC 的 H2 方言逐字节一致，MySQL 两侧都是反引号。**JPA 承诺范围只有 H2 + MySQL**：JPA 侧 `ReservedWordTest` 在 H2 与真 MySQL 上各 10/10 绿（H2 实抓渲染 `insert into "order" ("desc","key",name,id) ...`）；PG/MSSQL 的 JPA 组合未验证，Hibernate 在该两库上的引用形态属推断，Lead 侧探针报告过失败但失败 SQL 原文未保留、当前不可复现，不据此改 JPA 生产代码、也不引入 `columnQuoting` 类第二套配置。JDBC 侧适配规则见 [docs/DESIGN.md](docs/DESIGN.md) §6.1「方言接缝：`JdbcDialect` 的渲染钩子」（标识符引用规则与「DDL 与 SQL 必须同形」的约定同节），决策见 [docs/DESIGN.md](docs/DESIGN.md) §7 决策表。
- **已知限制（标识符内层定界符）**：引用标识符（反引号标注）剥引号后，名字里不能含引用定界符 `"` / `` ` `` / `[` / `]`——JDBC 在 meta 期抛 `UnsupportedOperationException`；JPA 侧 Hibernate 同样不转义内层引号（两后端一致不可用），故**两后端一致拒绝**，不为 JDBC 单侧放宽做内层转义。
- **LIKE / `contains` 的反斜杠语义与 JPA 对齐**：模式串里的 `%`/`_` 一律不转义（`contains` 即模式匹配，语义同 2.2.3），但字符串字面量里的 `\` 在绑参前翻倍，使反斜杠按字面解释——等价于 Hibernate 在 H2 的 `escape ''` 与在 MySQL 的 `replace(?, '\\', '\\\\')`；两方言 `TextMatchConsistencyTest` 同一批用例全绿。`@JoinColumn(referencedColumnName=...)` 采用三段式：空值放行（JPA 默认指向目标 `@Id`）、等于目标 `@Id` 实际列名（`@Column(name)` 优先）放行、其余指向非 `@Id` 列的 meta 期拒绝（UOE）；无 `@Version` 且无可写标量列的 `update` 明确拒绝（UOE），不静默丢语义。
- 接线：根 pom `modules`/`dependencyManagement` 与 `nextentity-bom` 增加两个模块；`nextentity-arch` 增加 `JDBC_IS_A_BACKEND_ONLY` 等规则（ArchUnit 只看 classpath，arch 的 test 依赖必须显式加 `jdbc`，否则规则匹配空集）；`tools/gate.sh` 的 `fast`/`pr`/`ci`/`night`/`release` 档注册 `jdbc-unit`/`tests-jdbc`/`tests-jdbc-it` 通道，`ci`/`night`/`release` 另注册方言矩阵通道 `ci-jdbc-pg`/`ci-jdbc-mssql`、`night-jdbc-it-pg`/`night-jdbc-it-mssql`、`release-jdbc-it-pg`/`release-jdbc-it-mssql`（`-Pjdbc-postgres` / `-Pjdbc-sqlserver`），`tools/gate-receipt.py` 认 `failsafe-pg:`/`failsafe-mssql:` 通道；`-DskipTests` 的 install 统一带 `-Djacoco.check.skip=true`。
- 设计正文 `docs/DESIGN.md`、使用正文 `docs/USAGE.md`。
- 本地门禁：Spotless(Palantir) + Checkstyle + PMD + SpotBugs + JaCoCo + Enforcer + ArchUnit + japicmp(API 兼容)。
- 新增 `nextentity-arch` 模块：ArchUnit 规则集中到一处，覆盖模块循环、`core` 包边界、`spec` 纯净与不可变、时间/随机注入、接口宽度、产物边界（api 的 class 只能来自 `nextentity-api`）。原 `core` 内的 `ArchitectureTest` 因 classpath 看不到 `jpa`/`spring` 而迁出。
- 静态检查补强：PMD 增加类级 NCSS、方法数、参数数、圈复杂度、耦合度、过宽 catch；Checkstyle 增加「catch 后返回默认值」拦截。
- `nextentity-api`：`Op` / `Operation`（19 个算子）、`LockMode`（5 常量，与 `jakarta.persistence.LockModeType` 同名）、`Path#refs()`、`SliceStep`、`Aggregates`，以及按「接口声明方法 ≤6」拆出的 `Comparison` / `Range` / `RangeIfNotNull` / `Nullness` / `Membership` / `Between` / `StringOps` / `PathOperator` / `StringOperator`。
- `nextentity-core`：`spec` 不可变 AST（`Comparison` / `Between` / `In` / `NullCheck` / `Logical` / `Constant` + `Projection` / `AggregateFunction` / `Selection` / `Ordering` / `Window` / `QuerySpec`）、`meta`（`EntityType` / `EntityAttribute`）、`builder`（步骤接口 → Spec、`Op` 解释器）、接缝（`Compiler` / `Executor` / `PersistExecutor` / `EntityOperationsFactory` / `EntityOperations` / `TransactionOperations`）。
- `nextentity-jpa`：`JpaMeta` / `JpaCompiler` / `JpaQueryBuilder` / `JpaPaths`（嵌套路径 LEFT JOIN + 前缀缓存）/ `JpaPredicates` / `JpaProjections` / `JpaExecutor` / `JpaPersistExecutor` / `JpaEntityOperationsFactory`。
- `nextentity-spring`：`NextEntityAutoConfiguration`（`nextentity.enabled=true` 时注册唯一的 `EntityOperationsFactory` bean）、`AbstractRepository`（构造器注入工厂 + `query()` + `insert(T)`）。
- `nextentity-testing` / `nextentity-tests-jpa`：后端无关的一致性基类 + SQL 快照工具（H2 `StatementInspector`），共 65 例。
- `nextentity-tests-legacy`：把 2.2.3 的 `integration` 用例移植过来做语义回归，1,063 条注解 → **727 条**（H2 与真 MySQL 8.0 各 727 条全绿，合计 1,454 次执行）。装置用真实 Hibernate 而非 Spring，建表用 2.2.3 自带 DDL + 驼峰转下划线命名策略，交付上下文前复位以保证用例隔离。第一轮按能力缺口删除 460 条；第一梯队 10 项能力落地后**回填 134 条**（`having`、多列 `groupBy`、`Tuple3~6`/`ProjectionMappers`、`min`/`avg`、数值算术、`not()`、`notBetween`、多参数 `orderBy` 累积链、多列 `distinct()`），仍删 336 条 + 3 个整类，逐条依据（含 api 源码 `文件:行`）与裁决见模块 `porting/REPORT.md`。
- **第一梯队能力（10 项）**，`api` 侧：列对列比较 `ColumnComparison`（`eq`/`ne`/`ge`/`gt`/`le`/`lt` 接 `Expression`）、表达式算术 `NumberArithmetic` + `NumberValue`（`add`/`sub`/`mul`/`div`/`mod`，可链式）、逻辑组合 `LogicalOps`（`not()` + n 元 `and`/`or(Collection)`）、聚合结果 `AggregateValue`（可比较/可区间、**不接聚合**）、`HavingStep`（`having(Expression)`）、`model.Tuple`（按位置取列的契约类型，实现是 `Tuple2`~`Tuple6` 的 record）、`Between` 增 `between(Expression,Expression)` 与 `notBetween(U,U)`、`StringOps.like(String)`（模式自带 `%`）、`SelectStep` 增三列投影重载（后收窄为固定三列 → `Tuple3`）、`GroupByStep` 增多列 `groupBy(PathRef...)`、`Aggregates` 增 `min()`/`avg()`（`avg` 返回 `Double`）、`NumberPath.sum()` 与全部聚合方法改为返回 `AggregateValue`、`Operation` 由 19 个常量扩到 27 个（新增 `NOT`/`MIN`/`AVG`/`ADD`/`SUB`/`MUL`/`DIV`/`MOD`）、`Op` 增 `subject()`（左侧表达式，`null` 表示主语就是 `refs()` 的路径）。
- **第一梯队能力（10 项）**，`core` / `jpa` 侧：`core.spec` 新增值表达式层 `Operand`（`Literal` / `AttributePath` / `AggregateOperand` / `ArithmeticOperand` + `BinaryOperator`）与 `ComputedProjection`，`Comparison`/`Between` 的字段升级为 `Operand`，`AggregateFunction` 增 `MIN`/`AVG`，`QuerySpec` 增 `having`（`forCount`/`forExists` 保留它）；`core.builder` 的 `OpInterpreter` 负责 `NOT`/`AND`/`OR` 的语义（`and(空)`/`or(空)` 都保留接收者（`a ∧ 空 = a`、`a ∨ 空 = a`）、`or` 混入短路条件=恒真）与 3 列以上 `TupleValue`；`jpa.compile.JpaOperands` 是 `Operand → Criteria Expression` 的**唯一**渲染点（聚合与算术的 `switch` 各只有一份），`JpaPredicates` 支持表达式右值，`JpaQueryBuilder` 渲染 `HAVING`。
- **显式投影映射器**，`api` 侧：新增 `ProjectionMapper<T, R>`（`expressions()` 声明列集、`map(Tuple)` 造结果）与 `SelectStep.project(ProjectionMapper)`。列集由映射器对象声明，结果直接是调用方类型 `R`，可在运行期组装列集合；**映射**零反射零注解（属性名解析仍靠方法引用的 `SerializedLambda`，与既有 `select(...)` 一致）。单列也以长度为 1 的 `Tuple` 交给 `map`（`Tuple#size()` 下限放宽到 1）。
- **投影装配只有一条路径**，`core` 侧：`spec.ValueSelection` 增加第三项 `rowMapper`（`RowMapper<?>`，「一行结果怎么装配成结果对象」），与列集 `items`、`distinct` 同属一份值——值查询一造出来就必有映射器，因此 `RowAssembler` 不再有「有列集却没映射器」需要运行期拒绝的状态，`select(...)` 与 `project(...)` 在这一层没有分支差异。`RowAssembler` 原来按「列数取自投影项个数、再 `switch (columns)`」造 `Tuple2`/`Tuple3`，映射器路径则一律取 `TupleValue`，同一个文件里两种装配口径（`select(...)` 走具名元组、`project(...)` 走按位置取值）；这套约定换成「列数只认 `items().size()`，一律包 `Tuple` 再 `map`」：`builder.ValueMappers` 提供 `single()`/`pair()`/`triple()` 三个内置映射器，`select(...)` 的元组结果改由它们造，单列行的裸值/长度 1 数组整形仍在 `RowAssembler#tuple`。`select(...)` 的可用列数不变（`SelectStep` 只有 1/2/3 列重载；任意列数经 `project(...)` + `ProjectionMappers` 本来就走映射器路径）。**行形态与投影列数不符时当场拒绝**：`RowAssembler#tuple` 在 `cells.length != items().size()` 时抛 `IllegalStateException` 并带上两个数字——旧实现在多列投影下不校验，短行在 `get(i)` 上抛 `ArrayIndexOutOfBoundsException`（后端错行被暴露），不校验的兜底会把它变成静默错值。**行为收窄（升级须知）**：旧 HEAD 里 `project(...)` 的单列投影**不校验行长度**，列值本身就是长度大于 1 的 `Object[]` 时原样返回该数组（旧用例 `ProjectionMapperTest#singleColumnWithArrayValue` 原本断言的就是「返回该数组」这一形状）；现在这类行按「行 cell 数 ≠ 投影列数」被拒，抛 `IllegalStateException`（该用例已改为断言拒绝，两个数字都在消息里）。单列投影的值若是数组列，需在映射器里显式处理，或改用多列 `project(...)` 形态取该列——这是「行形态不符必须暴露、不许静默」的直接代价。
- **显式投影映射器**，`core` 侧：新增 `builder.ValueProjections`（api 表达式 → `core.spec.Projection` 的唯一转换点，`select(...)` / `project(...)` 都经过它；映射器列集的整批校验也在这里，违规在 `project()` 阶段抛 `IllegalArgumentException` 且消息带列下标）与 `builder.RowAssembler`（后端原始行 → 结果对象：`Tuple` 装配与映射器映射），`QueryBuilder` 不再自己装配行。
- **去重修饰符** `api.DistinctStep#distinct()`（由 `SelectStep` 继承）：把「是否去重」从「投影形态」里正交出来，单列 / `Tuple2` / `Tuple3` / `ProjectionMappers` 的元组 / 自定义 `ProjectionMapper` 一视同仁。它返回 `SelectStep` 而非终止符，因此「声明了去重却没投影」在编译期不可写，`SELECT DISTINCT 实体` 也写不出来。多列去重是新能力（原先只有单列 `selectDistinct`）；多列去重的 `count()` 抛 `UnsupportedOperationException`——`COUNT(DISTINCT a, b)` 在 MySQL 与 PostgreSQL 形态不同，只数第一列会静默给出错数。
- **具名元组族 + 四~六列投影工厂**：`model.Tuple2`~`model.Tuple6` 全部改为 `api.model` 的 **record**（值语义、具名 `getN()`、按位置 `get(int)`），`core.builder.Tuple2Value` 随之删除；新增 `api.ProjectionMappers.select(...)`（四/五/六列）产出 `ProjectionMapper<T, Tuple4|Tuple5|Tuple6<...>>`，与 `ProjectionMapper#andThen(Function)` 组合子配合可换成调用方类型。类型安全来自元组在**调用点可见**的类型参数：列序写反 → 元组类型参数变 → 编译错；唯一一次 unchecked 转型只存在于 `ProjectionMappers` 一处。封顶六列是 `ExcessiveParameterListSix`（参数 ≤6）的直接后果，七列以上需自定义映射器。
- `SelectStep.select` 的 3 列重载**收窄为固定三列**并返回 `Tuple3<A, B, C>`（原先是 `Expression<T, ?>...` 变长、返回无类型的 `Tuple`）：三列的类型因此进入返回类型，列序写反从运行期错值变成编译错误。四列及以上不再有 `select` 重载，改走 `ProjectionMappers`。
- `model.Tuple#size()` 契约从「恒大于等于 2」放宽到「恒大于等于 1」：映射器路径单列也产出 `Tuple`，`Tuple` 类注释同步去掉「3 列及以上」的限定。
- `select(Expression)` / 2 列 / 3 列以上重载现在也接受 `PathRef`（裸方法引用）：它们与 `select(PathRef)` / `project(...)` 共用 `builder.ValueProjections.of(...)` 这条「api 表达式 → 投影项」转换，因此在静态类型被拓宽为 `Expression` 的 `PathRef` 上也能投影，不再抛 `IllegalArgumentException`（原先只有 `select(PathRef)` 重载能接，是既有的洞）。
- `nextentity-api` 有了自己的测试源码（`src/test`，140 条执行条目）：断言 `Op` 的四件套线格式（`operation()` / `arguments()` / `subject()` / `refs()` 逐级 `apply`），不依赖任何后端。此前该模块零测试，覆盖率全靠 `core` 的用例顺带贡献，而 PIT 有条硬门槛「模块没有测试源码就跳过」，所以 api 的变异门禁一直开不了。
- 新增两个**差分**一致性基类：`ColumnComparisonDifferentialTest`（列对列 6 个算子）与 `ArithmeticDifferentialTest`（`add`/`sub`/`mul`/`div`/`mod` 5 个算子）。oracle 是内存独立实现读同一份夹具原始值、按算子语义自己算期望行集，与真库执行结果逐行比对——补上了原先只有手算常量的那批用例缺的正确性证据。夹具抽出 `FixtureRow` / `Fixtures`（`ConsistencyTestBase.seed()` 改为从 `fixtureRows()` 映射，保证后端与内存 oracle 读同一份数据）。
- **L4（跨方言对拍）落地**：`nextentity-tests-jpa` 用 `testcontainers:mysql` 起一个 `mysql:8.0.18`（一个 JVM 一个容器，所有容器用例共享），把**同一批一致性基类**（14 个）加 4 类写路径场景接到真 MySQL 上，共 110 条。H2 用例走 surefire、`*IntegrationTest` 走 failsafe，两条通道按类名显式分流（failsafe 默认 includes 是 `**/IT*`/`**/*IT`/`**/*ITCase`，与项目约定的 `*IntegrationTest` 一个都不匹配，不显式配就会「surefire 全跑掉、failsafe 一条不收」）。实测 H2 与 MySQL 结果**零不一致**。
- 容器通道的开关 `-Dnextentity.requireContainer=true`：默认没有 Docker 就跳过（本地开发宽进），加上后 Docker 缺失会直接构建失败。`@Testcontainers(disabledWithoutDocker = true)` 只会跳过，让 110 条真方言对拍在无 Docker 的 CI 上悄悄退化成 110 条 skip 而构建照绿——这个开关就是用来堵这条静默降级路径的。
- **写侧维度覆盖（嵌入 / 列名覆盖 / 时间 / 自增主键）**：补上四条此前**一条用例都没有**的语义维度——非 legacy 的**持久化实体**里 `@GeneratedValue`、`Instant`/`LocalDate`、`@AttributeOverride` 零命中（唯一的 `Instant` 字段在 `core` 的 builder 单测里，不落库），`insert` 后的 id 回填、时间列的往返精度、嵌入字段写侧的摊平行为此前全靠读代码推断。新增**三份**契约基类覆盖这四个维度（`nextentity-tests-jpa`）：`EmbeddedOverrideWriteContract`（嵌入写侧 + `@AttributeOverride` 共用）、`TemporalWriteContract`、`GeneratedIdInsertContract`，每份只有一套断言、H2 与真 MySQL 子类只换接线，共 24 条 ×2 通道（其中 1 条是 PG 兼容模式专用用例，默认两通道各 skip 1 条，可数、不静默）。**结论：没有发现生产代码缺陷**——handoff 里怀疑的「`JpaPersistExecutor#setValues` 把 `@Embedded` 误当关联下发整块嵌入」实测不存在（Hibernate 7 会摊平成叶子列），但它依赖 Hibernate 实现，必须有常驻用例钉住；这四类都不需要新增生产代码，价值在于把「推断」换成「证据」。证据形态、变异注入验证与「注入必须带 `-am`」的坑见 [docs/DESIGN.md](docs/DESIGN.md) §5「写侧维度覆盖」。
- 容器镜像改走 `docker.xuanyuan.run` 镜像源（`docker.xuanyuan.run/library/mysql:8.0.18` 与 `docker.xuanyuan.run/testcontainers/ryuk:0.11.0`）：直连 `registry-1.docker.io` 在受限网络下会 `EOF`，而容器用例是 L4 的唯一载体，拉不到镜像等于这层没跑。用镜像站前缀必须给 `MySQLContainer` 显式 `asCompatibleSubstituteFor("mysql")`（它校验镜像仓库名），Ryuk 是 Testcontainers 另起的回收容器、默认也从 Docker Hub 拉，因此单独用 `ryuk.container.image` 指到同一个源。换源/换版本用 `-Dnextentity.mysql.image=` / `-Dnextentity.ryuk.image=` 覆盖。
- **CI 能在 GitHub 上真正跑起容器通道了**：`.github/workflows/{ci,nightly}.yml` 用 `GATE_EXTRA_MVN_ARGS` 把镜像源覆盖回 Docker Hub 原名（`library/mysql:8.0.18` / `testcontainers/ryuk:0.11.0`），因为根 pom 的默认源 `docker.xuanyuan.run` 在 GitHub runner 上不可达。`gate.sh` 新增该环境变量的解析（**只接受 `-D` 开头的属性**，防止 `-pl` / `-DskipITs` 这类会改变"跑了什么"的开关从环境里悄悄塞进来，绕过凭证的可核对性）。
- **`gate.sh` 首次拿到「不改 pom 换环境相关属性」的入口**：此前的命令行是写死的数组，`-Dnextentity.mysql.image=` 无从注入，导致「换源不必改 pom」这条 DESIGN 里的说法在 CI 上无法兑现。

### Changed

- **CI workflow 的 action 版本与 runner 镜像**：`actions/checkout` v4→v7、`actions/setup-java` v4→v6（v4 已被官方标记弃用）、`actions/upload-artifact` v4→v7，消掉「Node 20 被强制在 Node 24 上跑」的弃用告警。`runs-on` 从 `ubuntu-latest` **钉到 `ubuntu-24.04`**：`ubuntu-latest` 于 2026-10-19 起迁往 Ubuntu 26，镜像变更会带动 Docker 版本变化，而 `docker.api.version=1.44` 是实测出来的（Docker 29 对旧 API 直接回 400），让 runner 镜像静默漂移等于让容器通道的可用性失去控制变量。
- **主分支名 `master` → `main`**：`ci.yml` 的 `push.branches` 同步改掉（此前写的是 `[master]`，与仓库实际分支 `main` 不一致，push 触发的 CI 因此从未命中）。
- **`nextentity-api` 的零依赖门禁从「禁一切依赖」收窄为「只禁发布作用域」**：`bannedDependencies` 的 exclude 由 `*:*` 改为 `*:*:*:*:compile` / `:runtime` / `:provided` / `:system`，`test` 作用域放行。红线实质未变——发布产物仍零三方依赖（实测加 `org.slf4j:slf4j-api` 依然构建失败），松开的只是测试类路径，这样 api 才能有自己的单测与变异门禁。
- **`nextentity-jpa` 的变异测试从 `compile` 包扩到全模块**，阈值 90 → 95：全模块实测 125 个变异、约 28s，原先「exec/meta 每个变异都要起 Hibernate、太慢」的理由不成立。
- `count()` 用 `count(TRUE 字面量)`、`exists()` 用 `select TRUE` + `limit 1`，与 2.2.3 的 `SELECT_COUNT_ANY` / `SELECT_ANY` 逐行对应（对照表见 `tmp/contract/JPA-SEMANTICS.md` §3）。
- 嵌套关联路径（`EntityPath.of(A::getB).get(C::getD)`）固定走 **显式 LEFT JOIN**，与 2.2.3 相同；同前缀路径只 join 一次。
- `single()` 命中多行抛 `IllegalStateException("found more than one result, expected one")`（2.2.3 的 api Javadoc 写的就是 `IllegalStateException`，虽然其实现抛 `NextEntityException`；point-mall 无 `catch` 依赖）。
- 写入失败改抛标准 JPA 异常（`OptimisticLockException` / `EntityNotFoundException`），不再抛 2.2.3 的 `NextEntityException`。
- `lock(...)` 只接受自研 `LockMode`；2.2.3 的 `lock(jakarta.persistence.LockModeType)` 重载**已删除**，`api` 回归零三方依赖（消费方 3 处调用点改用 `LockMode.PESSIMISTIC_WRITE`，各 1 行）。
- 聚合方法（`count` / `countDistinct` / `max` / `min` / `avg` / `sum`）统一返回 `AggregateValue`（仍是 `Expression`）：`max().max()`、`count().count()` 这类"聚合再接聚合"的写法现在**编译期就不可写**（原先编译通过、运行期抛 `UnsupportedOperationException`）。
- 聚合 / 分组规格上的 `count()` 仍抛 `UnsupportedOperationException`（SQL 需子查询计数，3.x 未实现）；`exists()` 不受影响，语义是"有行"。`having(...)` 不校验"必须伴随 `groupBy`"——SQL 允许不带 `GROUP BY` 的 `HAVING`（全表聚合），由调用方负责。
- `a = b` 这类列对列比较按**数据库排序规则**执行：字符串列的大小写敏感性、尾随空格处理由方言决定（H2 与 MySQL 可能不同），不要拿它做跨库一致的业务语义。
- 单列 `distinct()` 的 `count()` / `.slice(...)` 现在真正可用：`JpaExecutor.count` 走 `count(distinct 投影列)`，不再显式拒绝（原先抛 `UnsupportedOperationException`）。
- `Op` 改为 `sealed`：`Op` 是「值 → AST」的线格式，语义由后端按 `operation()` 穷尽解释，不允许外部再实现出后端无法解释的运算。
- `Path` / `NumberPath` / `StringPath` 不再继承 `PathRef`：`PathRef` 只表示用户写的方法引用，路径值由新增的 `where(Path)` / `where(StringPath)` / `orderBy(Path)` 承载。**为什么必须破**：继承会让后端的 `where(PathRef)` 把 api 值对象当 lambda 解析（`writeReplace()` 抛 `NoSuchMethodException`）。
- `SelectStep` 增抽象方法 `project(ProjectionMapper)`：**接口的外部实现者需要补实现**（仓库内唯一实现是 `core.builder.QueryBuilder`）；调用方不受影响，既有 `select(...)` 重载的签名与语义未变。
- 删掉 4 处**死类型参数**：`SelectStep.select(3 列及以上)` 的 `<A, B, C>`（返回的是按位置取值的 `Tuple`，三列类型不进返回类型），以及 `Conditions` 两个私有辅助方法 `operands(Collection)` / `checked(Collection)` 的 `<T>`（只在唯一那个参数里出现一次，不约束任何第二个参数）。判断标准：类型参数至少出现两次、或用进返回类型才有意义。它们是已被否决的 `Tuple3<A,B,C>` 方案的残留。擦除后方法描述符不变，**二进制兼容**、调用点零改动。
- **条件位置的入参从「静默丢弃」改为「当场拒绝」**：`where(Expression)` / `having(Expression)` 收到既不是 `Op`、也不是短路占位（实现新增标记接口 `SkippedCondition` 的 `Conditions.AlwaysTrue`）的表达式时，原先整条条件被丢掉、查询退化成全表（`having` 那条连分组后的过滤一起没了）；`and`/`or` 的子条件里出现同类入参时，`or` 一侧还会被当成恒真、把前半条条件一起吃掉。现在四处统一抛 `IllegalArgumentException`，消息点名位置与正确写法（布尔字段请写 `.eq(true)`）；`lock(null)` 从"静默不加锁"改为 `NullPointerException`（`requireNonNull(mode)`）。**没有签名变更**，但这是行为收紧：靠"传错也能跑"的调用方会从拿到全表变成拿到异常——这正是目的，静默全表在租户/权限条件下是数据泄露级的错。
- **`nextentity-api` 新增公开标记接口 `SkippedCondition`**（零方法）：`*IfNotNull` / `*IfNotEmpty` 短路出来的恒真占位实现它，后端按该标记判定"这条条件没写"。此前判据是"不是 `Op` 的 `Predicate` 一律当占位"——`Op` 是 sealed 的、外部本来就造不出可下发节点，于是**第三方自己实现的 `Predicate` 会被当成占位、条件静默消失**（退化成全表）。改为标记接口后，第三方的谓词既没有节点也不是占位，按上一条当场拒绝。`OpInterpreter#isPlaceholder` 与 `Predicate` 的类 Javadoc 同步。
- `LogicalOps.or(Collection)` 的 Javadoc 与实现对齐：原写「`or(空)` 恒假，匹配不到任何行」，而实现（`Conditions.Leaf#or` / `AlwaysTrue#or`）与三条既有用例一致地把空集合当「没有可组合的条件」→ 返回接收者本身（`a ∨ 空 = a`）。文档按实现改正，并补一句「集合元素若是恒真占位则按 `a ∨ TRUE = TRUE` 处理，与空集合不是一回事」——这两件事此前在文档上完全分不开。

### Fixed

- `slice()` 遇到 NULL 投影值抛 `NullPointerException`：`select(可空列).slice(0, n)` 的每一行就是一个可能为 `null` 的值，而 `SliceResult` 用 `List.copyOf` 拷贝（`List.copyOf` 拒绝 `null` 元素）。2.2.3 的 `SliceImpl` 是纯 record、允许 `null`；`SliceResult` 改用 `Stream.toList()`（不可变且允许 `null`），`SliceResultTest` 与一致性用例钉住。
- `Slice#total()` 的口径写明：与 `Collector#count()` 同口径，`distinct()` 时是去重后的**非空**投影值个数——SQL 的 `COUNT(DISTINCT 列)` 不计 `NULL`，而 `SELECT DISTINCT` 把 `NULL` 算一组，因此「去重 + 可空列」的 `total()` 会比实际返回行数少 1。要精确行数请不要依赖去重投影的分页元数据。
- 去重投影 + 分页在真 MySQL 上必炸：`distinct().select(col).list(n)` / `first()` / `slice(o,l)` 会生成 `SELECT DISTINCT col ... ORDER BY 主键`，MySQL 报 `Expression #1 of ORDER BY clause is not in SELECT list`，而 H2 容忍——`QueryBuilder#spec(Window)` 自动补的主键排序没考虑去重投影。现在去重投影上不再补排序（稳定分页需显式 `orderBy`），`DistinctConsistencyTest#distinctSurvivesPaginationWithoutExplicitOrder` 钉住。
- 双重否定只生效一次：`not().not()` 的结果等于 `not()`。根因是 Hibernate 的 `Predicate.not()` 对「最内层是比较谓词」的嵌套取反**幂等**（纯 JPA 即可复现，H2 与 MySQL 同错），而 core 把 `NOT(NOT(x))` 原样下推。`OpInterpreter#negate` 现在消去双重否定（`NOT(NOT(x))` → `x`，三重 → 一层 `NOT`，`NOT(TRUE)`/`NOT(FALSE)` 折成常量），`NegationConsistencyTest` 在 H2 与真 MySQL 两条通道上钉住。
- `rules/checkstyle.xml` 的 FQN 规则把**合法的静态导入**判成违规：豁免正则只写了 `(?:import|package)\s+<前缀>`，漏了 `import static`，于是 `import static io.github.nextentity.api.WireFormatFixture.SALARY` 被报「禁止使用全类名」。四条规则（`io.github.nextentity` / `java` / `jakarta` / `org.springframework`）统一改成 `(?:import|package)\s+(?:static\s+)?<前缀>`。规则本意是拦「在代码里内联写全类名」，静态导入不属于这一类。
- `Path.of(...).<条件>.or(PathRef)` 之后的 `*IfNotNull(null)` 会把已挂上的前半条条件一起丢掉：`Conditions.PathOperatorValue` 的 `eqIfNotNull` / `geIfNotNull` / `gtIfNotNull` / `leIfNotNull` / `ltIfNotNull` / `inIfNotNull` 在参数为 `null` 时直接返回恒真占位，而**不是**只丢本次条件。于是 `where(x.eq(3).or(A::getScore).eqIfNotNull(null))` 退化成「无条件」，静默返回**全表**，正确结果应是 `x = 3`——`or(PathRef)` 的用途正是可选条件拼接，参数为 `null` 只表示「这条不加」。同一个算子名在 `core.builder.PathStepOperator` 里走 `skip.get()`，语义是「只丢本次条件」，两条入口此前相反。现在 `Conditions.AbstractOperators.skip()` 把三种情形分开（整条链已恒真 → 恒真；本次是唯一条件 → 恒真；有 `parent` → 原样交还），6 处短路改走它，两个入口语义一致。`ApiOperatorAssemblyTest` 的 6 条用例逐条钉住「`null` 侧结果与 `parent` 同一实例且 Spec 等于前半条、非 `null` 侧仍包成 `Leaf(OR, [parent, next])`、已恒真的链短路后仍恒真」。
- `nextentity-spring` 补上 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：`@AutoConfiguration` 只是标记，Spring Boot 2.7+ 靠该文件发现配置类。此前 jar 里没有这个文件，消费方引入依赖后**拿不到任何 bean**（`docs/USAGE.md` §12 的「引入即自动配置」并不成立）。原用例用 `AutoConfigurations.of(...)` 显式注册，绕过了注册表，所以一直没暴露；新增 `AutoConfigurationImportsTest` 直接读 classpath 上那个固定路径，把「能否被发现」钉住。
- `Path.of(...).in(...)` / `.notIn(...)` 的变长参数改用 `Arrays.asList`：原先用 `List.of(values)`，遇到 `null` 元素抛 `NullPointerException`，与 `Membership` 声明的「元素允许为 `null`」冲突，也与 `where(ref).in(...)`（走 `Arrays.asList`）行为不一致。同一算子两条入口现在一致，`OperatorMappingTest.membershipVarargsAcceptNullElement` 覆盖。
- 修正失效的 Javadoc / `@see` 引用：`api.DistinctStep` 指向 `core.spec.EntitySelection`（`api` 不依赖 `core`，链接永远解析不到，且暴露实现细节）；`jpa.compile.JpaOperands` 指向 private 的 `JpaPredicates#comparison`。
- 删除死代码：`Conditions.Leaf` 从未被调用的三参构造器、`PathValues.StringRoot`（无静态工厂可达，`StringPath` 没有 `of(...)`）、`Conditions.arithmetic` 恒为 `null` 的 `leftSubject` 形参。`Leaf` 与 `SimpleEntityOperationsFactory.Operations` 改为 record（组件名与 `Op` 的读取方法一一对应，不再手写 getter）。
- `JpaEntityAttribute` 构造器对 `entityType` 做 `requireNonNull`：错误消息里 `entityType.getName()` 在入参为 `null` 时会先抛 NPE，把「找不到字段」的真实原因盖掉。
- `nextentity-tests-legacy` 清理未使用 import / 未使用变量 / 未使用 logger 与指向已删除符号的 `@see`（该模块仍按「移植语料」保持与 2.2.3 同排版，仅清理无意义的死符号）。
- **关联嵌套路径的 17 个谓词格曾是同义反复**：夹具只有 `alpha` / `beta` 两个非空取值，于是 `gt(alpha) ≡ ne(alpha)`、`le(alpha) ≡ eq(alpha)`、`between(alpha,beta) ≡ isNotNull`、`like("al%") ≡ like("_lpha")`——把 `gt` 渲染成 `<>`、把 `between` 渲染成 `is not null`、把 `_` 通配丢掉，整族照样全绿（每格原有的"期望非空 / 非全表"自检挡不住这种退化）。夹具扩到 7 行（新增 `aardvark` < `alpha` < `alps` < `amber` < `beta` 五个可区分取值），结果集从 6 种涨到 15 种（17 格里只剩 `ne ≡ notIn` 与 `contains() ≡ isNotNull()` 两组语义本等价的同义反复），并新增 `AssociationDifferentialTest#predicateCellsAreMutuallyDistinguishable()` 逐对断言"该互相区分的格子期望集合确实不同"+ 一条按名字断言"重复的格组就是这两组"的兜底断言（不写裸数字：加一格不必改数字，撞车时直接点名是哪两格），夹具再退化就先在那里红。跨算子族的碰撞更隐蔽：`contains` 格原先取子串 `bet`，期望集合与 `ge(beta)` 完全相同，把 `contains` 渲染成 `>=` 全绿——子串改成 `am`（只命中 `amber`）并把 `contains` vs `ge` 加进配对；同理下划线通配格原模式 `_lpha` 与 `eq(alpha)` 同集合（把 `like` 渲染成 `=` 全绿），改成 `a___`（只命中 `alps`）并补 `eq(alpha)` vs `like(a___)` 这一对。
- **L2 的参数绑定取值补上一格结果断言**：`ItemQueryEntrySnapshotTest` 的悲观锁快照只钉语句骨架，而 `StatementInspector` 原理上拿不到绑定值——`where id = ?` 里绑错一个值，语句文本逐字节相同。新增 `pessimisticLockSingleReturnsTheFilteredRow` 用「取回的行就是 where 里那个 id，且名字对得上」兜住这一格；取行上限走 `setMaxResults`、与主键参数不是同一条通道，所以这一格真正兜住的是「条件参数绑成了别的值」。
- **`nextentity-api` 的短路占位多一条契约断言**：`Path.of(...)` 自己的短路（`PathValues` 里 `value == null ? Conditions.alwaysTrue() : ...`）若返回 `null` 而不是恒真占位，`where(...)` 那一层碰巧也"看不到条件"，只有继续链式组合（`Path.of(X).eqIfNotNull(null).or(Y::getScore)`）才会 NPE。新增 `PredicateOrOperatorTest#standaloneSkipReturnsPlaceholderNotNull` 钉住"非 null + 单例身份 + 链上追加条件仍短路"。同族的 `Conditions.AbstractOperators#skip()` 末行 `return parent == null ? alwaysTrue() : parent;` 经注入实验确认是**等价变异**（该分支不可达），结论写在 `docs/DESIGN.md` §5 的等价变异清单里。
- **`nextentity-jpa` 的 `update` 对关联列的行为此前无任何用例**：`JpaPersistExecutor#setValues` 跳过关联属性，改关联后 `update(entity)` 既不落库也不报错（SQL 里没有 `customer_id`）。新增 `JpaPersistAssociationTest` 覆盖四种关联形态并各钉两层：「关联不进 `SET`」+「同一行的基本属性照常更新」（只钉前者时把 `update` 整个改成空实现也能过）。形态分别是 `@ManyToOne`（`ShopOrder#customer`）、`@OneToMany`（`VersionedLedger#entries`，子表一行都不许动）、`@ManyToMany`（`Article#tags`，连接表 `article_tag` 一行都不许动）、`@OneToOne`（`SealHolder#seal`，唯一外键 `seal_id` 不进 `SET`）——后两种此前没有夹具，而连接表形态的跳过语义与子表形态并不等价。`@OneToOne` 的载体特意用零集合属性的 `SealHolder`：用同时带 `@ManyToMany` 的 `Article` 时，去掉关联判定后集合属性会先在 `CriteriaUpdate.set(集合属性)` 处抛异常，`doesNotContain("seal_id")` 那行根本执行不到。注入验证：把 `setValues` 的关联判定去掉后四条用例全红（`@ManyToOne` / `@OneToOne` 是断言失败，语句里出现 `customer_id` / `seal_id`；两种集合形态是 `set(集合属性)` 抛异常）。
- **三段嵌入路径此前没有用例**：`JpaPaths.navigate` 的递归只被两段路径（`Shipment#address.city`）覆盖过，嵌入里再嵌一层（`location.coordinates.zone`）零覆盖。新增 `JpaEmbeddedChainTest` 与四个夹具（`Warehouse` / `WarehouseLocation` / `GeoCoordinates` / `Datum`），用整句快照钉住三段与四段路径（`location.coordinates.datum.code`）的投影、条件、排序全部落在实体自己的列上：`select w1_0.id,w1_0.datum_code,w1_0.zone,w1_0.region from warehouse w1_0 where w1_0.zone=? order by w1_0.id`。四段那条还覆盖了前缀缓存第一次新建前缀项的一步。
- **`JpaMeta.entityType` 的缓存契约没有断言，且实现本身有并发窗口**：javadoc 承诺"同一实体多次调用返回同一实例"，但没有任何用例，把缓存退化成每次重建照样全绿（连带丢掉 `attributes()` 里缓存的字段句柄——只是身份与性能的变化，任何结果断言都看不出来）；同时 `get` → `if (cached == null)` → `of(...)` → `put` 是两步非原子序列，两个线程同时首访同一实体会各造一个实例、各自返回自己那份，契约在并发首访下不成立。实现改为 `cache.computeIfAbsent(type, t -> JpaEntityType.of(metamodel, t))`（构造性成立，窗口关掉）；`JpaMetaTest#entityTypeIsCachedPerEntity` 同时钉两个方向——同一实体三次调用 `isSameAs`、不同实体 `isNotSameAs`（只钉前者时"缓存键写错、任意类型都返回同一实例"照样绿）。注入验证：`if (cached == null)` → `if (true)` 时该用例红。
- **嵌入属性为 `null` 的行缺一条"必须返回"的断言**：`JpaPaths.navigate` 对 `@Embedded` 走 `get`，Hibernate 7 把嵌入 `join` 摊平成同一批列，所以「一律 join」这条变异在语句与结果上都不可观测（`docs/DESIGN.md` §5 判等价）。但等价依赖实现细节：若某实现给嵌入 `join` 加 `is not null` 谓词，嵌入列全 `null` 的行会静默消失。`JpaEmbeddedChainTest#nullEmbeddedRowIsReturned` 用一行 `location = null` 的夹具钉住"该行必须被查出来"，换大版本或接第二后端时先红。
- **`count()` / `exists()` 的条件此前只被平铺单属性走过**：这两个终止符各自复制了一份 where 下发逻辑（`exec/JpaExecutor.java:79` 的 `countQuery.where(...)`、`:102` 的 `existsQuery.where(...)`），而既有用例（`JpaPublicApiEndToEndTest`、`JpaDecimalAggregateTest`、`ItemAggregateTerminalContractTest`）全部走平铺条件。丢掉 where 的后果不对称且都静默：`count()` 数出全表行数、`exists()` 恒为 `true`。新增 `JpaPathsNestedTest#countThroughJoinedPath`（条件穿过 `@ManyToOne`：断言值 + 语句里同时有 `count(`、`left join customer …`、`where c1_0.name=?`）与 `#existsThroughEmbeddedPath`（条件穿过 `@Embedded`：命中 `true`、不命中 `false`，语句里条件落在 `s1_0.city` 上）。注入验证：把两处判定改成恒假后，两条用例分别红在 `:122`（值变 2）与 `:149`（语句缺 where）。语句断言用 `filteredOn(...).singleElement()` 而不是 `anySatisfy`——后者只保证"某条语句满足"，批次里多出一条语句时可能靠别的语句通过。
- **`distinct()` 旗标会残留到下一次投影**：`QueryBuilder` 的 `select(...)` / `project(...)` 先求值投影项、后取走 `distinct` 旗标；投影项抛 `IllegalArgumentException`（表达式类型不支持、映射器 `expressions()` 为空等）时旗标不会被取走，于是残留到下一次投影上——`count()` 从 `count(*)` 变成 `count(distinct 第一列)`，结果集与总数一起悄悄变小且无任何报错。现在 `takeDistinct()` 在求值投影项**之前**调用（旗标归「这次投影尝试」所有，尝试失败也要消耗掉），`QueryBuilderTest#distinctFlagIsConsumedEvenWhenProjectionFails` 与 `#distinctFlagIsConsumedEvenWhenMapperFails` 分别钉住两条投影入口。
- **`AbstractRepository` 泛型解析失败时 NPE 落在无关位置**：子类自身仍是泛型、被当 raw bean 注册、或中间隔了一层没写实参的父类时，`ResolvableType#resolveGeneric(0)` 返回 `null`，空指针会落在 `JpaMeta` 的 `ConcurrentHashMap`（不接受 `null` 键）里，栈里看不到是哪个仓库写错了泛型。构造器改为 `Objects.requireNonNull` 并点名仓库类与原因，`AbstractRepositoryTest#unresolvableEntityTypeIsRejectedWithRepositoryName` 钉住消息内容与「拒绝发生在取操作入口之前」（工厂的 `requestedTypes` 必须为空）。
- **写侧「跳过关联列」此前只有 H2 证据**：`JpaPersistExecutor#setValues` 跳过关联列这件事，断言只在 `nextentity-jpa` 的 H2 用例里（`JpaPersistAssociationTest:247`），容器通道一条都没有——把跳过条件改坏，H2 会红而真 MySQL 通道静默放过。新增容器用例 `MySqlAssetWriteSkipsAssociationIntegrationTest`（3 条）：语句层断「`update asset` 的 SET 里没有 `owner_id`」，结果层断「库里的外键仍是原值」（片段断言挡不住「多写一条 UPDATE 再覆盖回来」），再加一条 `insert` 的正向对照（证明跳过只作用于 `update`）。
- **方言矩阵实测（opt-in，默认行为不变）**：`HibernateHarness#start` 按系统属性 `-Dnextentity.h2mode` 分派（判定逻辑是纯函数 `resolveMode(String)`），缺省 `mysql` 时 URL 逐字节不变，`postgresql` 时改连 `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH` 并钉 `PostgreSQLDialect`，未知取值直接抛 `IllegalArgumentException`（不静默回退）。**不读环境变量**：fork 出来的 surefire JVM 会继承它，一条 `NEXTENTITY_H2MODE=postgresql` 就能让后续所有构建静默换方言而日志里看不出来。第一次实测（H2 侧 535 条）：25 红、零生产缺陷，其中 16 条指向 **oracle 不中立**（排序/分组/关联差分的内存侧内建了 MySQL 的「ASC 时 NULL 在前」），已按下一条修掉；第二次实测（539 条，当时基线；现 540 条，多出的是开关自身的纯函数用例）剩 8 红，全部是可归因的方言差异——3 条 SQL 文本快照（`ItemSqlSnapshotTest` / `ItemProjectionMapperRenderingTest` / `ItemTier1RenderingTest`）与 5 条锁模式（H2 的 PG 兼容模式不认 `for no key update`、`for share`；`ItemQueryEntrySnapshotTest` 的两条按 cause 归后者），其余 531 条（现 532 条）两方言断言逐字相同。开关自身的正确性另有 `HibernateHarnessModeTest`（5 条纯函数用例，不连库）：MySQL 的 URL 与历史逐字节一致、PG 的三个兼容开关一个不少、方言只在 PG 下钉、未知取值失败而非静默回退、NULL 序映射与方言一致——这几行的错误形态都是「默认通道照样全绿」，只能直钉。第五次实测（H2 侧 564 条，补完第三轮审查整改之后）：仍是 8 红、零生产缺陷；`Instant` 偏移从「小于 24 小时的容忍带」改为精确断言（PG 兼容模式恰好 `-1 ×` JVM 偏移，MySQL 模式恒等），两个通道的 skip 计数都恒为 1。结论与后续方向记在 `docs/DESIGN.md` §5。
- **排序/分组差分的 oracle 绑死了 MySQL 的 NULL 序**（`NULLS FIRST` vs `NULLS LAST` 被当成语义而不是方言事实）：`ORDER BY x` 里 `null` 排前还是排后是后端事实，写死在一致性基类里等于把「某方言碰巧如此」冒充成规格——换后端时要么整族用例假红，要么被人把断言放宽成「只钉非空部分」而失去证据力（`AssociationConsistencyTest` 的嵌套路径排序断言就曾是这种放宽形态）。现在两族都改为显式传入、默认值仍是 MySQL，因此既有断言逐字未变：`ConsistencyTestBase#nullOrdering()`（默认 `NULLS_FIRST`，`SortDifferentialTest` / `HavingDifferentialTest` 的 oracle 全部读它）与 `AssociationDifferentialTest` 的构造器参数（该族方法数已顶到 PMD `TooManyMethods`，参数走构造器既不占方法位也不可遗忘）；两族的 JPA 适配器从 `HibernateHarness#nullOrdering(H2Mode)` 取值（`MYSQL` → `NULLS_FIRST`，`POSTGRESQL` → `NULLS_LAST`），无参 `nullOrdering()` 只负责读属性再转调；H2 侧的 `ItemSortDifferentialTest` / `ItemHavingDifferentialTest` / `ItemAssociationDifferentialTest` 读属性，**容器通道的三个差分类各自钉死 MySQL**（两个覆写 `nullOrdering()`、关联族构造器传 `H2Mode.MYSQL`），因为它们连的是真 MySQL，带 `-Dnextentity.h2mode=postgresql` 跑容器通道时若沿用属性值，排序/分组 oracle 会用 `NULLS_LAST` 去比真 MySQL 的 `NULLS_FIRST`，整族假红。证据是 PG 模式复测：修前 16 条排序/分组/关联差分全红，修后全绿。
- **Spring 侧的「真实接线」只有 H2 证据**：`nextentity-spring` 的 `NextEntityRealBackendTest` 跑的是 H2 兼容模式，而「自动配置 + `SharedEntityManagerCreator` 的事务绑定代理 + 真方言」这一组合此前零覆盖——把代理换成一个非事务绑定的 `EntityManager`，H2 版照样会绿。新增容器用例 `MySqlSpringRepositoryIntegrationTest`（4 条）：自动配置产出的工厂直接喂给 `AbstractRepository` 子类，在真 MySQL 上断「回滚真丢数据」「本线程未提交的行对另一线程不可见」（插入若被静默自动提交就会立刻变红）「4 线程并发各查各的行」。用例自己的实体 `SpringWiredEntity` 独占一张表，理由是**行集隔离**：多个容器类都在断言精确行集，共用一张表会互相污染（`create-drop` 不是理由，它只处理本 PU 映射的表）。
- **容器通道与其余模块此前跑在两套 JUnit 大版本上**：`nextentity-tests-jpa` 引入 `spring-boot-dependencies` BOM 后，该 BOM 自带的 `junit-bom:6.0.1` 与本仓库根的 `junit-bom:5.11.4` 竞争——junit 走的是 **import**，两个 import 之间先声明者赢，于是 `junit-jupiter` 被抬到 6.0.1，而 `core` / `jpa` 仍是 5.11.4。只钉 `junit-jupiter` 不够：`junit-platform-commons` / `-engine` 会留在 6.0.1，同一模块里 jupiter 5.11.4 配 platform 6.0.1（540 条照样能跑，属于运气而不是门禁）。改为在 Boot BOM **之前** import `org.junit:junit-bom:${junit.version}`，`dependency:tree -Dincludes=org.junit.platform` 实测回落 `1.11.4`。顺带记一笔：h2 / assertj / testcontainers 没漂，是因为根 pom 对它们是**直接** dependencyManagement 条目（直接声明优先于 import），钉住是为了防未来。

### Removed

- `AbstractRepository<T, ID>` 的 `ID` 类型参数：本类从不使用它，Javadoc 里也写着「仅用于子类声明的可读性」。**为什么必须破**：未使用的类型参数会误导子类以为主键类型参与契约，且它让 `AbstractRepository` 的签名与 `EntityOperations` 的泛型面不一致。迁移是机械替换：`extends AbstractRepository<Order, Long>` → `extends AbstractRepository<Order>`（README、`docs/USAGE.md` 与本仓库示例已同步）。
- 相对 nextentity 2.x 不再提供：自研元模型、实体事件总线、CGLIB 仓储代理、`Tuple5`~`Tuple10`（2.x 的 JDBC 第二引擎已由 3.0.0 的 `nextentity-jdbc` 以新形态重新提供）。
- `api` 包 44 个类型整体移除（完整清单与逐条理由见下方 Migration 第 1 节）。
- `core.expression.*`、`core.meta.impl.DefaultMetamodel`、`core.util.Iterators`、`core.EntityTemplateFactory`、`spring.EntityFactoryBuilder` 等 2.x 内部符号不再提供。
- `SelectStep.selectDistinct(PathRef)`：由 `distinct().select(ref)` 取代。**为什么必须破**：两者语义完全相同，留着就是两个入口；而补「多列 `selectDistinct` 重载」会让「投影形态 × 是否去重」变成笛卡尔积（4 × 2 = 8 个入口），并把 `SelectStep` 从 6 个方法推到 8 个、撞穿 `API_INTERFACES_STAY_NARROW` 的 ≤6。迁移是机械替换：`x.selectDistinct(f)` → `x.distinct().select(f)`，仓库内 5 处移植用例与 point-mall 2 个调用点（`DigitalCardRepository:85`、`OrderImportRecordRepository:71`）均已替换。
- `core.TransactionOperations`：零实现、零调用的空接缝已删除（事务边界由调用方 `@Transactional` 持有）。需要第二后端时随它一起引入，不提前摆空接口。

### 不提供的能力

- 自定义方言的 `quote` 只作用于**查询渲染**（表名 / SELECT 列 / WHERE·ON 列 / GROUP BY·HAVING·ORDER BY 列）：
  INSERT/UPDATE 与建表 DDL 恒用裸名，`JdbcPersistExecutor` 不接方言、`WriteSql` 不调 `quote`。内置四种方言的 `quote`
  均为恒等，故对开箱用法无影响；需要引用型标识符（保留字/大小写敏感）时，DDL 与写侧裸名要能接受，或自行在列名/表名层规避。
  生成键回读用的列标签也不 quote（驱动返回原始 label）。

### Migration

**0. 消费方实测结论（point-mall 2.2.3 → 3.0.0）**

- 改 2 行版本号 + 重写 1 个仓储基类实现文件即可切换；25 个引用 `io.github.nextentity` 的文件中 24 个零改动。
- 版本号（`pom.xml` 两行）：
  ```xml
  <nextentity.version>3.0.0-SNAPSHOT</nextentity.version>
  <id.generator.version>2.0.0-SNAPSHOT</id.generator.version>
  ```
- 唯一需要改代码的地方：若曾像 point-mall 的 `AppRepositoryImpl` 那样直接 import 2.x 内部类（`DefaultMetamodel`、`EntityTemplateFactory`、`SimpleExpressionImpl`、`PathNode`、`Iterators`），改为通过 `core.EntityOperationsFactory` 取 `EntityOperations`：`query()` 查询、`insert/insertAll/update/updateAll` 写入、`entityType().version()` 取 `@Version` 属性回写。
- 回滚只需把上面两行改回 `2.2.3` / `1.3.3`（无 schema 变更、无数据迁移）。

**1. 整类移除（44 类，按原因归组）**

| 移除的类型 | 为什么必须破 |
|---|---|
| `ExpressionBuilder` + `PathOperator`/`NumberOperator`/`StringOperator`/`Conjunction`/`Disjunction` | 2.2.3 用 50+ 方法的单接口承载全部算子，违反「接口声明方法 ≤6」；3.x 拆成 7 个窄算子接口 + 2 个顶层算子接口 |
| `SimpleExpression` / `StringExpression` | 同上拆分后的空壳；`count/countDistinct/max` 迁到 `Aggregates`，字符串算子迁到 `StringOps` |
| `EntityRoot` | 2.x 的 `api` 通过它 `import core.util.DefaultEntityRoot`（api→core 倒挂）；3.x `api` 零依赖后不存在，改由 `Path.of(...)` 的 api 内值实现承担 |
| `EntityContext` / `EntityDescriptor` / `EntityOperations` / `EntityPersistor` / `Executable` / `Entity` / `Persistable` | 全库 0 引用；写入面由消费方自己的仓储接口提供，3.x 不再重复定义持久化契约 |
| `FetchStep` / `SubQueryBuilder` | `fetch(...)` / `toSubQuery()` 全库 0 调用点；`having(...)` 原先 0 调用点，第一梯队已按新契约重建为 `api.HavingStep`（见 Added），与 2.2.3 的"分组后步骤"同名不同义 |
| `UpdateSetStep` / `UpdateWhereStep` / `DeleteWhereStep` | `update().set().where()` / `delete().where()` 0 调用点；写入走仓储级 `insert/update/insertAll/updateAll` |
| `WhereStep` | 仅用于承载 8 个 `where` 重载的中间接口；3.x 由 `BaseWhereStep` + `Whereable` 承担 |
| `BooleanPath` / `PathRef.BooleanRef` | 0 引用；布尔属性走 `where(..).eqIfNotNull(b)` |
| `PathRef.BigDecimalRef`/`ByteRef`/`DoubleRef`/`FloatRef`/`IntegerRef`/`LongRef`/`ShortRef` / `PathRef.EntityPathRef` | 0 引用；数值统一由 `PathRef.NumberRef` 承载，关联统一走 `EntityPath` |
| `model.Tuple3`~`Tuple10` | 不再提供具名元组（`get2()`…`get9()`）；3 列及以上统一返回 `model.Tuple`（`get(int)`/`size()`），`Tuple` 因此按新契约重建而非删除 |
| `model.Order` / `SortOrder` | 0 引用；排序只保留 `orderBy(PathRef\|Path) → asc()/desc()` |

**2. 方法移除 · 收窄（同类合并）**

| 类型 | 移除/收窄 | 为什么必须破 |
|---|---|---|
| `Collector` | `exists(int)` / `list(int,int)` / `toSubQuery()` 移除；`slice(int,int)` 迁到 `SliceStep` | 前三个 0 调用点；`Collector` 已顶到 6 个方法上限 |
| `Collector` | `first()` / `list(int)` 由 default 变 abstract | 2.2.3 的 default 实现 import 了 `core.expression.SliceImpl` + `core.util.ImmutableList`（api→core 反向依赖） |
| `model.Slice` | `offset()` / `limit()` / `map(Fn)` 移除；`to(Fn)` default 变 abstract | 前三个 0 调用点（`offset(` 全是 `Pageable.offset()`）；`to()` 同理依赖 core 实现 |
| `model.Tuple2` | 接口 → **record**（`A get0, B get1`），`get(int)` 与 `size()` 显式实现 | 与 `Tuple3`~`Tuple6` 统一形态；仓内唯一实现 `core.builder.Tuple2Value` 已删，point-mall 只用 `Tuple2::get0/get1` 与 `List<Tuple2<...>>`，零改动 |
| `SelectStep` | 44 个投影重载 → 5 个：`select(PathRef)`、`select(Expression)`、`select(Expression,Expression)`（→`Tuple2`）、`select(Expression,Expression,Expression)`（→`Tuple3`）、`project(ProjectionMapper)` | 其余均 0 调用点；三列、四~六列与映射器是新能力（见 Added） |
| `Whereable` | 8 个 `where` → 5 个：移除 `where(NumberRef)`、`where(NumberPath)`、`where(EntityPathRef)` | 三个重载 0 调用点；保留的 5 个全部有调用点 |
| `GroupByStep` / `OrderByStep` | 变长与 `Collection` 重载全部移除 | 0 调用点（`groupBy` 6 处、`orderBy` 138 处全是单参形态） |
| `OrderOperator` | 移除 14 个终止符 default 转发 | 虚空防御：`OrderOperator` 只该给方向，终止符由 `BaseWhereStep` 继承 `Collector` 提供 |
| `NumberExpression` | 整类删除（原先只承载 `sum()` 与 `max()` 的收窄返回类型）；`sum()` 移到 `NumberPath`，聚合方法统一返回 `AggregateValue` | 它存在的唯一效果是让"聚合再接聚合"能编译通过、运行期抛异常；删掉后该写法在编译期即被拒绝 |
| `StringExpression` | 移除 `notLike/notContains/notStartsWith/notEndsWith`、`startsWith/endsWith`、`lower/upper/substring/trim/length` 及其 `*If*` 变体 | 0 调用点（point-mall 里这些全是 `java.lang.String` 的方法） |
| `Predicate` | 移除 `ofTrue()` / `ofFalse()` 与变长 `and`/`or`（`not()` 与 n 元 `and`/`or` 已按新契约重建为 `LogicalOps`，见 Added） | 0 调用点；短路由「不实现 `Op` 的 `Predicate`」表达，恒假由后端从 `IN` + 空参推导 |
| `EntityPath` / `Path` / `NumberPath` / `StringPath` / `Expression` | 移除 `of(String)`、`of(BooleanRef)`、`of(EntityPathRef)`、`apply(T)`、`get(NumberRef)` 等类型不安全或 0 调用的重载 | 0 调用点；`of(String)` 是 DESIGN §4 明令移除的类型不安全重载 |

**3. 形状变化（只改返回类型/修饰符，调用点可解析）**

| 类型 | 变化 | 为什么必须破 |
|---|---|---|
| `SelectStep` / `GroupByStep` | `select(...)` / `groupBy(...)` 返回 `BaseWhereStep` | `WhereStep` 删除、`HavingStep` 按新契约重建（`having` 入口）后，中间步骤只剩 `BaseWhereStep`；调用点只链 `where/orderBy/slice/list` |
| `OrderOperator` | `asc()` / `desc()` 返回 `BaseWhereStep` | 去掉「步骤顺序自由化」的中间接口，避免 `groupBy` 之后还能 `.where()` 的非法顺序 |
| `Whereable` | `where(...)` 返回顶层 `PathOperator` / `StringOperator` | 算子接口拆分后类型名随之变化 |
| `LockStep` | 只保留 `lock(api.LockMode)`；`lock(jakarta.persistence.LockModeType)` 重载删除 | `api` 零三方依赖；3 处存量调用点改用自研枚举，各 1 行 |
| `Path` / `NumberPath` / `StringPath` | 不再继承 `PathRef` / `PathRef.NumberRef` / `PathRef.StringRef` | 继承会让后端的 `where(PathRef)` 把 api 值对象当 lambda 解析（`writeReplace()` 抛 `NoSuchMethodException`） |

**4. japicmp 报告口径**

`-Papi-compat -Djapicmp.oldVersion=2.2.3` 使用 `breakBuildBasedOnSemanticVersioning=true`：主版本升级内的二进制破坏不使构建失败，但报告仍会生成（`nextentity-api/target/japicmp/japicmp.{md,xml}`，254 KB）。原始报告统计为「移除 44 类 / 新增 14 类 / 修改 18 类」；上表按**功能**归组，因此同一处破坏不会重复计数（原始 xml 里「返回类型所属接口被改名」会同时出现 `METHOD_REMOVED` + `METHOD_MODIFIED`，属工具口径）。

**5. 与 2.2.3 的有意差异（不影响存量调用点）**

| 差异 | 说明 |
|---|---|
| `fetch(...)` 无对应能力 | 只影响 SQL 形状/性能，不影响结果集；point-mall 0 调用点 |
| `@Version` 支持 `Integer` / `Long` | 2.2.3 只支持 `Integer`；point-mall 版本列全为 `integer` |
| 分组计数需投影非空列 | SQL 的 `count(列)` 不数 NULL，需要「组内实体行数」时应投影主键等非空列（point-mall 6 处分组查询均如此） |
| `distinct()` 的 `count/slice` | 单列已实现为 `count(distinct 投影列)`；多列抛 `UnsupportedOperationException`（跨方言形态不同）。去重后行数与实体行数是两个概念，勿混用 |

