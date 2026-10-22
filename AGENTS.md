# AGENTS.md（项目规范入口）

> 本文件是规范入口：只放**规则与命令**。架构、用法、变更各有唯一归属，见文末「文档地图」——其它文档只引用此处条目，不重复写红线。

# 行为约束

- **公开 API 是契约**：`io.github.nextentity.api` 下的签名受兼容门禁保护。破坏性变更每版 ≤ 5 处，且必须同时具备三样：机械替换脚本（codemod 或编译错误驱动）、`japicmp` 影响面报告、CHANGELOG 写明"为什么必须破"。
- **版本语义**：`api` 包遵循语义化版本，删除/收窄签名即主版本；`core`/`jpa`/`spring` 内部包标记 `@Internal`，不承诺兼容。
- **依赖方向单向**：只允许 `api ← core ← jpa ← spring`。`api` 零三方依赖；`core` 不得 import `jakarta.persistence` / `hibernate` / `spring`。反向依赖与循环依赖由 enforcer + ArchUnit 拒绝。执法者：各模块 `bannedDependencies`（编译期）+ `nextentity-arch` 的 `MODULES_FREE_OF_CYCLES` / `CORE_PACKAGES_FREE_OF_CYCLES`。
- **新能力只落一处**：新谓词 = `spec` 加节点 + 编译器加分支；新终止符 = `exec` 加方法；不得在 builder 里拼 SQL 或拼 Criteria。新增节点必须让编译期穷尽性检查报错，以此逼出全部分支。
- **接口要窄**：新增公开接口的方法数 ≤ 6；超过说明边界切错了。执法者：`nextentity-arch` 的 `API_INTERFACES_STAY_NARROW`。
- **可测试性**：时间/随机必须经 `Clock` / `IdGenerator` 注入，禁止直调 `System.currentTimeMillis()` / `Instant.now()` / `new Random()`；禁止静态可变字段；禁止 catch 后返回默认值。执法者：`nextentity-arch` 的 `NO_AMBIENT_CLOCK_OR_RANDOM` / `NO_MUTABLE_STATIC_STATE`，以及 `rules/checkstyle.xml` 的 catch 规则。
- **能力必须有测试**：每个能力至少 1 条 SQL 快照（`nextentity-testing`）+ 1 条一致性用例；只加功能不加测试不得合入。
- **一个 MR 一件事**：破坏性 API 变更不得夹带功能变更。
- **临时脚本放 `tmp/scripts/`**，用完即删；不得读取 `tmp/` 下非本次会话生成的文件。
- **移植语料是范围例外**：`nextentity-tests-legacy` 是 2.2.3 用例的移植载体，源文件刻意与上游同排版以便逐文件 diff，故该模块跳过 spotless/checkstyle/PMD。它是**回归证据**，不是风格样板，也不得把它的写法抄进其它模块；改这个模块只许改调用形态，不许弱化断言（细则见模块 `README.md` 与 `porting/REPORT.md`）。
- **不写死凭据**：示例配置一律用占位符，真实连接信息只走本地环境变量。

# 验证规范（Agent 必须遵守）

| 本次改了什么 | 必须执行 |
|---|---|
| 仅文档/注释（`*.md`、Javadoc） | 跳过构建 |
| `api` / `core` / `spec` / `compile` / `exec` | `mvn -q spotless:apply` → `mvn -pl nextentity-core,nextentity-arch -am test` |
| `jpa` / `spring` | `mvn -q spotless:apply` → `mvn -pl nextentity-arch -am test` → `mvn -pl nextentity-tests-jpa verify`（需 Docker） |
| `nextentity-tests-legacy` | `mvn -pl nextentity-tests-legacy test`（默认 H2，无容器）；动到方言相关再跑 `-Ddbc=h2,mysql` |
| 公开签名变更 | 额外 `mvn -Papi-compat -Djapicmp.oldVersion=<上一发布版> -pl nextentity-api verify` |
| 发版前 | `mvn clean verify`（格式 + 静态检查 + 单测 + 覆盖率门槛），更新 CHANGELOG |
| 文档里的代码示例（`*.md` 的代码块） | 先把示例**原样**抄进 `tmp/scripts/probe/DocExamples.java`，`javac` 对相关模块真实 `target/classes` 编译通过后才写进文档；文档声称的能力必须能在源码里指到 `文件:行`，指不到就删掉或移入「不提供的能力」表 |

- **报「绿」三件套**：commit hash + `git status --short` 为空 + 落盘日志路径；跨模块存在未提交改动期间的口头/单点「绿」一律无效（本轮迁移里它抓出 3 次假绿：陈旧 `.m2` 产物、api 中间态编译错误、flaky 集成用例）。
- **不读陈旧产物**：跨模块验证前先 `mvn install`（或一律 `-am` 反应堆构建）；`~/.m2` 里的 `-SNAPSHOT` 可能落后于当前 HEAD，用它跑出的结论只能证明"旧产物能跑"。
- **测试只跑一遍**：一律 `mvn ... > tmp/test-output/<场景>.log 2>&1`，从落盘日志（及 surefire-reports）看全结论；结论得出后删除日志，**禁止为补看日志重跑**。
- **分层跑测试**：`api`/`core` 单测无需容器；`nextentity-arch` 只跑架构规则（无容器，改任何模块都要跑）；`nextentity-tests-jpa` 是集成测试（H2 + Testcontainers MySQL），需 Docker，只在改动 `jpa`/`spring` 或发版前跑。
- **测试命名**：快测试 `*Test`，落库场景 `*IntegrationTest`；集成测试启动容器后须在 `@AfterEach` 复位被改状态。
- **提交信息**：Conventional Commits（`feat:` / `fix:` / `refactor:` / `test:` / `docs:`），一行一件事；PR 说明受影响模块 + 本地实际跑过的命令。

# 命令速查

```bash
./mvnw clean verify                      # 全量门禁：Spotless + Checkstyle + PMD + SpotBugs + 单测 + 覆盖率
./mvnw -q spotless:apply                 # 一键格式化（提交前必跑）
./mvnw -pl nextentity-core,nextentity-arch -am test   # 快速回归 + 架构门禁，无需容器
./mvnw -pl nextentity-tests-jpa verify   # 集成测试：H2 + Testcontainers MySQL（需 Docker）
./mvnw -pl nextentity-tests-legacy test  # 2.2.3 遗留语义回归：H2，无容器
./mvnw -pl nextentity-tests-legacy test -Ddbc=h2,mysql   # 同上，加真 MySQL（需 Docker 或外部实例，见模块 README）
./mvnw -Papi-compat -Djapicmp.oldVersion=3.0.0 -pl nextentity-api verify   # API 兼容检查
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
