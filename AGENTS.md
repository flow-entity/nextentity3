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
| `nextentity-tests-legacy` | `mvn -pl nextentity-tests-legacy -am test`（默认 H2，无容器）；动到方言相关再跑 `-Ddbc=h2,mysql -Dnextentity.requireContainer=true`（**不带 require 时容器起不来会静默降级成只跑 H2**，见 DESIGN §5「通道拆分」） |
| `core` / `jpa` 新增或修改**生产代码** | 额外 `mvn -pl nextentity-<模块> pitest:mutationCoverage`，变异分数不得低于模块 pom 的阈值（**PIT 不带 `-am`**，跑前先 `mvn install -DskipTests` 刷 `~/.m2`，理由见下表 api 行） |
| `spring` 新增或修改**生产代码** | 额外 `mvn -pl nextentity-spring pitest:mutationCoverage`（阈值 95，同样先 install）。注意 PIT 不变异注解，`@ConditionalOnProperty` 的开关语义由 `NextEntityAutoConfigurationTest` 的用例兜，不看分数 |
| `api` 新增或修改**生产代码** | 额外 `mvn -pl nextentity-api pitest:mutationCoverage`（api 有自己的线格式用例，门禁自足，阈值 95）。api 的语义同时被 `core` 的用例执行，所以 `core` 的 PIT 也要跑；**跑任何 PIT 之前必须先 `mvn install -DskipTests`**——`pitest:mutationCoverage` 不带 `-am`，它从 `~/.m2` 解析依赖模块，陈旧快照会让依赖新语义的用例集体报「未变异就不通过」，看着像测试坏了，其实是产物旧了 |
| 公开签名变更 | 额外 `mvn -Papi-compat -Djapicmp.oldVersion=2.2.3 -pl nextentity-api -am verify`。两个坑：①基线 artifact 是 **`nextentity-core`**（2.2.3 没发布过 `nextentity-api`，`io.github.nextentity.api.*` 的类在 `nextentity-core-2.2.3.jar` 里）；②示例版本号必须是中央仓库**真能解析到**的（`3.0.0` 从未发布，抄下来只会报「artifact 无法解析」）。**当前这条只产报告、不判红**：`breakBuildBasedOnSemanticVersioning=true` 在 `3.0.0-SNAPSHOT ← 2.2.3` 这种主版本跃迁下允许一切破坏（实测 624 处不兼容仍 `BUILD SUCCESS`），所以「破坏性变更每版 ≤ 5 处」这条红线**现在没有执法者**，3.0.0 发布后基线挪到 `3.0.0` 才真正生效 |
| 发版前 | `./tools/gate.sh release`（档位构成见 [docs/DESIGN.md](docs/DESIGN.md) §5「执行凭证」），并更新 CHANGELOG |
| 文档里的代码示例（`*.md` 的代码块） | 先把示例**原样**抄进 `tmp/scripts/probe/DocExamples.java`，`javac` 对相关模块真实 `target/classes` 编译通过后才写进文档；文档声称的能力必须能在源码里指到 `文件:行`，指不到就删掉或移入「不提供的能力」表 |

- **报「绿」三件套**：commit hash + `git status --short` 为空 + `receipt.json` 路径（`tools/gate.sh` 产出；手跑单点测试时用落盘日志路径）；跨模块存在未提交改动期间的口头/单点「绿」一律无效（本轮迁移里它抓出 3 次假绿：陈旧 `.m2` 产物、api 中间态编译错误、flaky 集成用例）。
- **不读陈旧产物**：跨模块验证前先 `mvn install`（或一律 `-am` 反应堆构建）；`~/.m2` 里的 `-SNAPSHOT` 可能落后于当前 HEAD，用它跑出的结论只能证明"旧产物能跑"。
- **构建要独占工作树**：并行跑多个 `mvn` 会共用同一个 `target/`，造出「jar/classes 里缺刚写好的类」这类假红，也会让下一次构建静默复用被写坏的产物（`-am` 与 `-Dmaven.repo.local` 都挡不住，冲突在 `target/` 不在本地仓库）。要并行就各开一个 `git worktree`，或串行排队；看到「明明存在的类找不到」先怀疑产物被覆盖，**不要改代码去凑绿**；日志里出现 `Nothing to compile - all classes are up to date` 时，要意识到本次复用的是前序产物。
- **测试只跑一遍**：一律 `mvn ... > tmp/test-output/<场景>.log 2>&1`，从落盘日志（及 surefire-reports）看全结论；结论得出后删除日志，**禁止为补看日志重跑**。
- **分层跑测试**：`api`/`core` 单测无需容器；`nextentity-arch` 只跑架构规则（无容器，改任何模块都要跑）；`nextentity-tests-jpa` 是落库测试，H2 用例走 surefire、`*IntegrationTest` 走 failsafe 跑真 MySQL 容器（`testcontainers:mysql`，见 [docs/DESIGN.md](docs/DESIGN.md) §5「通道拆分」），只在改动 `jpa`/`spring`/`nextentity-testing` 或发版前跑。**CI 必须加 `-Dnextentity.requireContainer=true`**：默认无 Docker 时整条容器通道计为 skip、构建照样绿（本地开发要这个宽进），CI 不加就等于把 L4 变成纸面门禁；加上之后 Docker 不可用会直接构建失败。
- **静态检查以凭证为准**：checkstyle / PMD / SpotBugs 的结论不认 `mvn` 退出码——`failOnViolation` 改 false、`violationSeverity` 抬高、删掉模块 pom 里的插件 `<execution>` 块，三种都能让 `mvn verify` 退出 0 而报告缺席或带违规。`tools/gate-receipt.py` 为每个模块立 `static:<模块>` 通道，自己数报告里的违规条目（checkstyle `error` / PMD `violation`，被抑制的不算 / SpotBugs `BugInstance`），**缺席、不新鲜、有违规三条任一即红**。期望集按模块形状推导（`pom` 打包只要 checkstyle、有 `src/main/java` 要三件套、纯测试模块要 checkstyle+PMD），**不从模块 pom 的插件配置读**——删执行块时若期望一起消失，检查就等于没写；仓库明文例外（`nextentity-tests-legacy` 是移植语料）以 `STATIC_EXEMPT` 列在凭证工具里，改它要动门禁脚本本身。因此「我本地 verify 过了」不构成静态检查的证据，只有 `receipt.json` 里的 `static_violations` 构成。
- **测试命名**：快测试 `*Test`，落库场景 `*IntegrationTest`；集成测试启动容器后须在 `@AfterEach` 复位被改状态。
- **提交信息**：Conventional Commits（`feat:` / `fix:` / `refactor:` / `test:` / `docs:`），一行一件事；PR 说明受影响模块 + 本地实际跑过的命令。

## 测试编写红线

违反任一条的用例按**无效证据**处理，不计入「能力必须有测试」。

- **oracle 要分层**：手算常量只许钉已知语义（闭区间、NULL 行归属、算子互补这类细节）；正确性证据必须来自差分（真库执行 vs 独立内存计算）、跨方言对拍，或变形关系（`Q(p)` 必须等于 `Q(p∧c)` ∪ `Q(p∧¬c)` ∪ `Q(p∧c IS NULL)` 这类全称成立的关系）。只有手算常量的新用例，按「未提供正确性证据」退回。三者不互相替代：差分答「算得对不对」，变形关系答「oracle 自己错了算不算数」，跨方言答「方言无关性」——实测把 `GE` 改成 `GT` 时 TLP 全绿、差分全红，选错层就等于没测。口径见 [docs/DESIGN.md](docs/DESIGN.md) §5「oracle 分层」。
- **mock 只许证形状**：mock `EntityManager` / `TypedQuery` / `CriteriaBuilder` 最多证明「调用形状对」；不得作为某能力的唯一正确性证据。
- **禁用要有租约**：`@Disabled("issue=<链接或编号> expires=<yyyy-MM-dd>")`，两个字段缺一即违规；过了 `expires` 仍在禁用，按违规处理。
- **一致性基类保持后端中立**：该守哪几条在 [docs/DESIGN.md](docs/DESIGN.md) §5「编写约定」里，此处不再重述；评审逐条对照，命中即违规。
- **契约拒绝必须钉住**：任何抛 `UnsupportedOperationException` 的分支必须有 `assertThatThrownBy` 用例；缺用例的拒绝分支视为能力缺口被掩盖，不得合入。
- **方言跳过必须可数**：按方言跳过的用例一律用显式标记（注解或 `@Tag`），不得只在注释或日志里交代。跳过条数**由凭证判定**：`tools/gate.sh` 给每个通道申报 skip 预算（默认 0），`tools/gate-receipt.py` 从 surefire/failsafe XML 读 `<skipped>` 连理由汇总到 `receipt.json` 的 `skip_report` 字段，**超预算即红**。所以 PR 说明不手写清单：直接贴凭证里的 `skip_report.total` 与条目。
- **预期失败要有台账且会腐烂**：某通道上「按设计就会红」的用例不许用 `@Disabled` 藏（既不跑、也看不出哪天修好），必须逐条写进 `tools/expected-failures/<通道>.txt`，每条带 `type=`、`issue=` 与 `expires=`。判据是**双向完全相等**：实际失败集多一条 = 新回归，少一条 = 基线腐化（已修好仍留着条目），过期未续也算违规；`type`（`failure` / `error`）也必须相等——断言不符悄悄变成被测代码抛异常时，名字和条数都不变。`expires` 不得超过今天 + 180 天，空基线文件按配置错误处理。只许在 `tools/gate.sh` 里由 `STEP_EXPECTED_FAILURES` 点名的步骤生效；申报了基线的通道若不在该步的 `--require` 里，同样判红（「申报了却被静默忽略」）；「测试红但生命周期不中断」由 `nextentity-tests-jpa/pom.xml` 的 `pg-dialect` execution 上的 `<testFailureIgnore>true</testFailureIgnore>` 承担，好让 `verify` 阶段的 checkstyle/PMD/SpotBugs/jacoco 照常执行；**不要**用 `-Dmaven.test.failure.ignore=true` 全局属性代替——它会把同一反应堆里 api/core/jpa/spring 的失败一并吞掉。判定权交给凭证。方言矩阵通道还要求与基础通道**跑同一批用例**（条数相等），少跑即红。
- **变异分数不许靠降阈值换来**：PIT 分数只回答「杀死了多少」，剩下的变异体必须逐条进 `tools/equivalents/<模块>.txt` 台账（`类 | 行 | 方法 | 算子 | 条数 | reason=… | expires=…`），`reason` 要写清「改了什么、为什么行为不变」。判据与预期失败同款：与 PIT 报告**双向**比对——实际未杀死却不在台账 = 漏测；台账里有而报告里没有 = 台账腐化；同位置条数不符 = 算子集或代码结构变了；到期未续 = 违规；空台账按配置错误处理。`mutationThreshold` 只许在「确实写不出能杀它的用例、且已逐条论证」时下降，不许为了过门禁下调。根 pom 的 `<mutators>` 必须显式关掉 `-FRECORD`——PIT 默认开启它，会把**整个** record 类的变异全部吃掉（`nextentity-core` 的 27 个 spec 节点曾因此完全隐形）。跑 PIT 前必须先 `test-compile`：`pitest:mutationCoverage` 自己不编译，陈旧的 `target/test-classes` 会让它拿旧用例去杀新代码，看着像「用例写不出来」，其实是没编。

# 命令速查

```bash
./tools/gate.sh fast                     # 日常（无容器）
./tools/gate.sh pr                       # 提 PR 前（无容器）
./tools/gate.sh ci                       # CI 权威档（容器必须真跑）
./tools/gate.sh night                    # 夜间（含 PIT）
./tools/gate.sh release                  # 发版前（含 PIT + api 兼容，需 OLD_VERSION）
# 各档的具体步骤见 DESIGN §5「执行凭证」；判定只认 tmp/gate/<commit>-<ts>/receipt.json，
# 不以日志末行为准。

./mvnw clean verify                      # 子模块自查（非权威）：格式 + 静态检查 + 单测 + 覆盖率；不含容器通道
./mvnw -q spotless:apply                 # 一键格式化（提交前必跑）
./mvnw -pl nextentity-core,nextentity-arch -am test   # 快速回归 + 架构门禁，无需容器
./mvnw -pl nextentity-tests-jpa -am verify   # 落库测试：H2 走 surefire + MySQL 容器走 failsafe（-DskipITs 只跳容器通道）
./mvnw -pl nextentity-tests-jpa -am verify -Dnextentity.requireContainer=true   # CI 用：Docker 不可用时失败而不是静默跳过
./mvnw -pl nextentity-tests-legacy -am test  # 2.2.3 遗留语义回归：H2，无容器
./mvnw -pl nextentity-tests-legacy -am test -Ddbc=h2,mysql -Dnextentity.requireContainer=true   # 同上，加真 MySQL（需 Docker 或外部实例，见模块 README）；不带 require 时容器不可用会静默只跑 H2
./mvnw -Papi-compat -Djapicmp.oldVersion=2.2.3 -pl nextentity-api -am verify   # API 兼容报告（基线是 nextentity-core:2.2.3；主版本跃迁下只产报告不判红，见验证规范）
./mvnw -pl nextentity-api test-compile pitest:mutationCoverage    # 变异测试：api，阈值 95%（门禁自足，用例在本模块）
./mvnw -pl nextentity-core test-compile pitest:mutationCoverage   # 变异测试：core，阈值 95%
./mvnw -pl nextentity-jpa test-compile pitest:mutationCoverage    # 变异测试：jpa 全模块，阈值 95%
./mvnw -pl nextentity-spring test-compile pitest:mutationCoverage # 变异测试：spring（委派与条件装配），阈值 95%
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
