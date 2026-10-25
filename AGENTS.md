# AGENTS.md（项目规范入口）

> 本文件是规范入口：只放**规则与命令**。架构、用法、变更各有唯一归属，见文末「文档地图」——其它文档只引用此处条目，不重复写红线。

# 行为约束

- **公开 API 是契约**：`io.github.nextentity.api` 下的签名受兼容门禁保护。破坏性变更每版 ≤ 5 处，且必须同时具备三样：机械替换脚本（codemod 或编译错误驱动）、`japicmp` 影响面报告、CHANGELOG 写明"为什么必须破"。
- **版本语义**：`api` 包遵循语义化版本，删除/收窄签名即主版本；`core`/`jpa`/`spring` 内部包标记 `@Internal`，不承诺兼容。
- **依赖方向单向**：只允许 `api ← core ← jpa ← spring`。`api` 零三方依赖；`core` 不得 import `jakarta.persistence` / `hibernate` / `spring`。反向依赖与循环依赖由 enforcer + ArchUnit 拒绝。执法者：各模块 `bannedDependencies`（编译期）+ `nextentity-arch` 的 `MODULES_FREE_OF_CYCLES` / `CORE_PACKAGES_FREE_OF_CYCLES`。
- **新能力只落一处**：新谓词 = `spec` 加节点 + 编译器加分支；新终止符 = `exec` 加方法；不得在 builder 里拼 SQL 或拼 Criteria。新增节点必须让编译期穷尽性检查报错，以此逼出全部分支。
- **接口要窄**：新增公开接口的方法数 ≤ 6；超过说明边界切错了。执法者：`nextentity-arch` 的 `API_INTERFACES_STAY_NARROW`。
- **可测试性**：时间/随机必须经 `Clock` / `IdGenerator` 注入，禁止直调 `System.currentTimeMillis()` / `Instant.now()` / `new Random()`；禁止静态可变字段；禁止 catch 后返回默认值。执法者：`nextentity-arch` 的 `NO_AMBIENT_CLOCK_OR_RANDOM` / `NO_MUTABLE_STATIC_STATE`，以及 `rules/checkstyle.xml` 的 catch 规则。
- **能力必须有测试**：每个能力至少 1 条 SQL 快照（`nextentity-tests-jpa`）+ 1 条一致性用例（`nextentity-testing`）；只加功能不加测试不得合入。
- **一个 MR 一件事**：破坏性 API 变更不得夹带功能变更。
- **临时脚本放 `tmp/scripts/`**，用完即删；不得读取 `tmp/` 下非本次会话生成的文件。
- **移植语料是范围例外**：`nextentity-tests-legacy` 是 2.2.3 用例的移植载体，源文件刻意与上游同排版以便逐文件 diff，故该模块跳过 spotless/checkstyle/PMD。它是**回归证据**，不是风格样板，也不得把它的写法抄进其它模块；改这个模块只许改调用形态，不许弱化断言（细则见模块 `README.md` 与 `porting/REPORT.md`）。
- **不写死凭据**：示例配置一律用占位符，真实连接信息只走本地环境变量。

# 验证规范（Agent 必须遵守）

| 本次改了什么 | 必须执行 |
|---|---|
| 仅文档/注释（`*.md`、Javadoc） | 跳过构建 |
| `api` / `core` / `spec` / `compile` / `exec` | `mvn -q spotless:apply` → `mvn -pl nextentity-core,nextentity-arch -am test` |
| `jpa` / `spring` | `mvn -q spotless:apply` → `mvn -pl nextentity-arch -am test` → `mvn -pl nextentity-tests-jpa -am verify`（含 MySQL 容器通道；无 Docker 时自动跳过，见 DESIGN §5「通道拆分」） |
| `nextentity-testing`（一致性基类、夹具、`ConsistencySupport`） | `mvn -q spotless:apply` → `mvn -pl nextentity-tests-jpa -am verify`（含 MySQL 容器通道；无 Docker 时自动跳过，见 DESIGN §5「通道拆分」） |
| `nextentity-tests-legacy` | `mvn -pl nextentity-tests-legacy -am test`（默认 H2，无容器）；动到方言相关再跑 `-Ddbc=h2,mysql` |
| `core` / `jpa` 新增或修改**生产代码** | 额外 `mvn -pl nextentity-<模块> pitest:mutationCoverage`，变异分数不得低于模块 pom 的阈值 |
| `spring` 新增或修改**生产代码** | 额外 `mvn -pl nextentity-spring pitest:mutationCoverage`（阈值 95）。注意 PIT 不变异注解，`@ConditionalOnProperty` 的开关语义由 `NextEntityAutoConfigurationTest` 的用例兜，不看分数 |
| `api` 新增或修改**生产代码** | 额外 `mvn -pl nextentity-api pitest:mutationCoverage`（api 有自己的线格式用例，门禁自足，阈值 95）。api 的语义同时被 `core` 的用例执行，所以 `core` 的 PIT 也要跑；**跑它之前必须先 `mvn -pl nextentity-api install -DskipTests`**——`pitest:mutationCoverage` 不带 `-am`，它从 `~/.m2` 解析 `nextentity-api`，陈旧快照会让依赖新语义的用例集体报「未变异就不通过」，看着像测试坏了，其实是产物旧了 |
| 公开签名变更 | 额外 `mvn -Papi-compat -Djapicmp.oldVersion=<上一发布版> -pl nextentity-api verify` |
| 发版前 | `mvn clean verify`（格式 + 静态检查 + 单测 + 覆盖率门槛），更新 CHANGELOG |
| 文档里的代码示例（`*.md` 的代码块） | 先把示例**原样**抄进 `tmp/scripts/probe/DocExamples.java`，`javac` 对相关模块真实 `target/classes` 编译通过后才写进文档；文档声称的能力必须能在源码里指到 `文件:行`，指不到就删掉或移入「不提供的能力」表 |

- **报「绿」三件套**：commit hash + `git status --short` 为空 + `receipt.json` 路径（`tools/gate.sh` 产出；手跑单点测试时用落盘日志路径）；跨模块存在未提交改动期间的口头/单点「绿」一律无效（本轮迁移里它抓出 3 次假绿：陈旧 `.m2` 产物、api 中间态编译错误、flaky 集成用例）。
- **不读陈旧产物**：跨模块验证前先 `mvn install`（或一律 `-am` 反应堆构建）；`~/.m2` 里的 `-SNAPSHOT` 可能落后于当前 HEAD，用它跑出的结论只能证明"旧产物能跑"。
- **构建要独占工作树**：并行跑多个 `mvn` 会共用同一个 `target/`，造出「jar/classes 里缺刚写好的类」这类假红，也会让下一次构建静默复用被写坏的产物（`-am` 与 `-Dmaven.repo.local` 都挡不住，冲突在 `target/` 不在本地仓库）。要并行就各开一个 `git worktree`，或串行排队；看到「明明存在的类找不到」先怀疑产物被覆盖，**不要改代码去凑绿**；日志里出现 `Nothing to compile - all classes are up to date` 时，要意识到本次复用的是前序产物。
- **测试只跑一遍**：一律 `mvn ... > tmp/test-output/<场景>.log 2>&1`，从落盘日志（及 surefire-reports）看全结论；结论得出后删除日志，**禁止为补看日志重跑**。
- **分层跑测试**：`api`/`core` 单测无需容器；`nextentity-arch` 只跑架构规则（无容器，改任何模块都要跑）；`nextentity-tests-jpa` 是落库测试，H2 用例走 surefire、`*IntegrationTest` 走 failsafe 跑真 MySQL 容器（`testcontainers:mysql`，见 [docs/DESIGN.md](docs/DESIGN.md) §5「通道拆分」），只在改动 `jpa`/`spring`/`nextentity-testing` 或发版前跑。**CI 必须加 `-Dnextentity.requireContainer=true`**：默认无 Docker 时整条容器通道计为 skip、构建照样绿（本地开发要这个宽进），CI 不加就等于把 L4 变成纸面门禁；加上之后 Docker 不可用会直接构建失败。
- **测试命名**：快测试 `*Test`，落库场景 `*IntegrationTest`；集成测试启动容器后须在 `@AfterEach` 复位被改状态。
- **提交信息**：Conventional Commits（`feat:` / `fix:` / `refactor:` / `test:` / `docs:`），一行一件事；PR 说明受影响模块 + 本地实际跑过的命令。

## 测试编写红线

违反任一条的用例按**无效证据**处理，不计入「能力必须有测试」。

- **oracle 要分层**：手算常量只许钉已知语义（闭区间、NULL 行归属、算子互补这类细节）；正确性证据必须来自差分（真库执行 vs 独立内存计算）或跨方言对拍。只有手算常量的新用例，按「未提供正确性证据」退回。口径见 [docs/DESIGN.md](docs/DESIGN.md) §5「oracle 分层」。
- **mock 只许证形状**：mock `EntityManager` / `TypedQuery` / `CriteriaBuilder` 最多证明「调用形状对」；不得作为某能力的唯一正确性证据。
- **禁用要有租约**：`@Disabled("issue=<链接或编号> expires=<yyyy-MM-dd>")`，两个字段缺一即违规；过了 `expires` 仍在禁用，按违规处理。
- **一致性基类保持后端中立**：该守哪几条在 [docs/DESIGN.md](docs/DESIGN.md) §5「编写约定」里，此处不再重述；评审逐条对照，命中即违规。
- **契约拒绝必须钉住**：任何抛 `UnsupportedOperationException` 的分支必须有 `assertThatThrownBy` 用例；缺用例的拒绝分支视为能力缺口被掩盖，不得合入。
- **方言跳过必须可数**：按方言跳过的用例一律用显式标记（注解或 `@Tag`），不得只在注释或日志里交代。skip 清单**由凭证自动抽取**（`tools/gate-receipt.py` 从 surefire/failsafe XML 读 `<skipped>` 连理由，汇总在 `receipt.json` 的 `skips` 字段），所以 PR 说明不再手写：直接贴凭证里的 `skip 总数` 与清单，并说明本次增删的来由。凭证里出现无理由跳过、或总数比上次上升，按违规处理。

# 命令速查

```bash
./tools/gate.sh fast                     # 日常：格式化 + 架构规则（无容器，~1 分钟）
./tools/gate.sh pr                       # 提 PR 前：H2 564 + 遗留 727（无容器）
./tools/gate.sh ci                       # CI：pr + 真 MySQL 容器通道 + requireContainer=true
./tools/gate.sh night                    # 夜间：install 刷快照 + 全量含容器 + 遗留双跑 + PIT×4
./tools/gate.sh release                  # 发版：clean verify + api-compat
# 五档都落 tmp/gate/<commit>-<ts>/receipt.json（通道条数 / skip 清单 / PIT 分数 / 产物新鲜度），
# 判定只认这份凭证；「绿」以凭证为准，不以日志末行为准。

./mvnw clean verify                      # 全量门禁：Spotless + Checkstyle + PMD + SpotBugs + 单测 + 覆盖率
./mvnw -q spotless:apply                 # 一键格式化（提交前必跑）
./mvnw -pl nextentity-core,nextentity-arch -am test   # 快速回归 + 架构门禁，无需容器
./mvnw -pl nextentity-tests-jpa -am verify   # 落库测试：H2 走 surefire + MySQL 容器走 failsafe（-DskipITs 只跳容器通道）
./mvnw -pl nextentity-tests-jpa -am verify -Dnextentity.requireContainer=true   # CI 用：Docker 不可用时失败而不是静默跳过
./mvnw -pl nextentity-tests-legacy -am test  # 2.2.3 遗留语义回归：H2，无容器
./mvnw -pl nextentity-tests-legacy -am test -Ddbc=h2,mysql   # 同上，加真 MySQL（需 Docker 或外部实例，见模块 README）
./mvnw -Papi-compat -Djapicmp.oldVersion=3.0.0 -pl nextentity-api verify   # API 兼容检查
./mvnw -pl nextentity-api pitest:mutationCoverage    # 变异测试：api，阈值 95%（门禁自足，用例在本模块）
./mvnw -pl nextentity-core pitest:mutationCoverage   # 变异测试：core，阈值 95%
./mvnw -pl nextentity-jpa pitest:mutationCoverage    # 变异测试：jpa 全模块，阈值 95%
./mvnw -pl nextentity-spring pitest:mutationCoverage # 变异测试：spring（委派与条件装配），阈值 95%
```

从仓库根目录执行；单模块内工作时才 `cd`。首次克隆后执行 `git config core.hooksPath githooks` 启用提交前门禁。

# 编码约定

4 空格缩进、标准 Java 花括号。包名小写（`io.github.nextentity.*`），类/接口 PascalCase，方法/字段 camelCase，常量 UPPER_SNAKE_CASE。API-first：方法名短而明确，只在行为不显然处写 Javadoc；沿用 `*Repository` / `*Provider` / `*Factory` 后缀。

# 文档地图（每个要求只有一个出处）

| 文件 | 唯一职责 |
|---|---|
| [README.md](README.md) | 门面：定位、30 秒示例、导航 |
| **本文件** | **规则与命令**：红线、版本语义、验证命令、编码约定 |
| [docs/DESIGN.md](docs/DESIGN.md) | **架构正文**：模块与依赖、四层职责、兼容契约、测试策略、第二后端接缝、决策与否决 |
| [docs/USAGE.md](docs/USAGE.md) | **使用正文**：条件、排序、分页、聚合、投影、关联、CRUD、Spring 接入 |
| [CHANGELOG.md](CHANGELOG.md) | **变更正文**：每版变更、破坏性说明、迁移指引 |

**不单独建文档**（避免同一要求分散）：

| 曾考虑 | 去向 |
|---|---|
| 独立 ADR 目录 | DESIGN.md「决策与否决」 |
| COMPAT.md 兼容矩阵 | 本文件两条红线 + DESIGN.md「兼容契约」 |
| TESTING.md 测试规范 | DESIGN.md「测试策略」 |
| VERSIONING.md / RELEASING.md | 本文件「版本语义」「验证规范」 |
| `docs/versions/<v>/` | CHANGELOG.md |
| 按主题拆分的 guides（9 篇） | USAGE.md |
