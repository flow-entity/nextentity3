package io.github.nextentity.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.github.nextentity.api.Collector;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/// 架构门禁：依赖方向、包边界与可测试性约束由测试守住，而不是靠自觉。
///
/// 本模块只有测试代码，随 `mvn verify` 与 `mvn test` 一起执行。独立成模块的原因：
/// ArchUnit 只能看到 classpath 上的类，规则放在 `nextentity-core` 里就永远看不到
/// `nextentity-jpa` / `nextentity-spring`，跨模块约束会静默失效。
@AnalyzeClasses(packages = "io.github.nextentity", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /// `api` 接口的公开方法数上限（AGENTS「接口要窄」）。
    private static final int API_MAX_PUBLIC_METHODS = 6;

    /// 允许超过上限的既有接口：类型全名 → **该接口自己的**声明数上限（精确值，不是「放宽到 N」）。
    ///
    /// 条目只能用于「既有公开接口恢复 2.2.3 就有、3.0.0 丢掉的成员」这一类场景，
    /// 因为拆接口会改 builder / `lock(...)` 的返回类型，属主版本动作，MINOR 里做不了。
    /// 规则：**只许持平、不许增长**（数值就是精确上限，多一个方法立刻红），
    /// 每条必须在 [docs/DESIGN.md](../../../../../../../docs/DESIGN.md) §7 的决策表里有对应条目，
    /// 并写明 revisit 触发器（下一次主版本按注释里的拆分口径落地）。
    ///
    /// `Collector`：2.2.3 的 7 个终止符（`list()` / `list(int)` / `list(int,int)` / `first()` /
    /// `single()` / `count()` / `exists()`）是同一个契约面；3.0.0 丢掉了 `list(int,int)`，
    /// 3.1.0 恢复它导致声明数回到 7。拆分点（取行 vs 聚合）只有在主版本才能重新划。
    private static final Map<String, Integer> WIDE_INTERFACE_ALLOWLIST = Map.of(Collector.class.getName(), 7);

    /// 模块之间不得出现循环依赖。
    @ArchTest
    static final ArchRule MODULES_FREE_OF_CYCLES = slices().matching("io.github.nextentity.(*)..")
            .should()
            .beFreeOfCycles()
            .because("模块依赖必须单向：api ← core ← jpa ← spring");

    /// `core` 内部各包之间不得互相缠绕。
    @ArchTest
    static final ArchRule CORE_PACKAGES_FREE_OF_CYCLES = slices().matching("io.github.nextentity.core.(*)..")
            .should()
            .beFreeOfCycles()
            .because("spec / meta / builder / compile / exec / repo 之间必须单向，否则分层只存在于文档里");

    /// Spec 是纯值对象：不得携带后端与 IO 依赖。
    @ArchTest
    static final ArchRule SPEC_IS_BACKEND_FREE = noClasses()
            .that()
            .resideInAPackage("io.github.nextentity.core.spec..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "jakarta..", "org.hibernate..", "org.springframework..", "java.sql..", "java.io..", "java.nio..")
            .because("Spec 必须可断言、可序列化、可被任意后端复用");

    /// Spec 节点一旦构造就不得再变化。
    @ArchTest
    static final ArchRule SPEC_NODES_ARE_IMMUTABLE = classes()
            .that()
            .resideInAPackage("io.github.nextentity.core.spec..")
            .should()
            .haveOnlyFinalFields()
            .because("可变节点会让快照断言与跨后端复用同时失真");

    /// 禁止直接读系统时钟或创建随机源。
    ///
    /// **构造器禁令必须写全参数表**：ArchUnit 1.4.1 的单参 `callConstructor(Random.class)`
    /// 只匹配**无参**构造器（它把 `parameterTypes` 拼进 `rawParameterTypes` 精确相等谓词，
    /// 空 varargs 只等于空列表），`new Random(seed)` 会静默漏过。所以无参与带 seed 两条都写。
    ///
    /// **不用** `dependOnClassesThat().areAssignableTo(RandomGenerator.class)` 一刀切：
    /// 它按类型依赖判（字段/参数/返回值都算），而本规则作用的范围包含
    /// `nextentity-testing/src/main`——那正是「把随机源注入进来」的合法写法所在地，
    /// 一刀切会把 AGENTS.md 要求的注入式夹具判红。注入 `RandomGenerator` 不是环境依赖。
    @ArchTest
    static final ArchRule NO_AMBIENT_CLOCK_OR_RANDOM = noClasses()
            .should()
            .callMethod(System.class, "currentTimeMillis")
            .orShould()
            .callMethod(System.class, "nanoTime")
            .orShould()
            .callMethod(Instant.class, "now")
            .orShould()
            .callMethod(LocalDate.class, "now")
            .orShould()
            .callMethod(LocalDate.class, "now", ZoneId.class)
            .orShould()
            .callMethod(LocalDateTime.class, "now")
            .orShould()
            .callMethod(LocalDateTime.class, "now", ZoneId.class)
            .orShould()
            .callMethod(LocalTime.class, "now")
            .orShould()
            .callMethod(LocalTime.class, "now", ZoneId.class)
            .orShould()
            .callMethod(ZonedDateTime.class, "now")
            .orShould()
            .callMethod(ZonedDateTime.class, "now", ZoneId.class)
            .orShould()
            .callMethod(OffsetDateTime.class, "now")
            .orShould()
            .callMethod(OffsetDateTime.class, "now", ZoneId.class)
            .orShould()
            .callMethod(OffsetTime.class, "now")
            .orShould()
            .callMethod(OffsetTime.class, "now", ZoneId.class)
            .orShould()
            .callMethod(Year.class, "now")
            .orShould()
            .callMethod(Year.class, "now", ZoneId.class)
            .orShould()
            .callMethod(YearMonth.class, "now")
            .orShould()
            .callMethod(YearMonth.class, "now", ZoneId.class)
            .orShould()
            .callMethod(Clock.class, "systemUTC")
            .orShould()
            .callMethod(Clock.class, "systemDefaultZone")
            .orShould()
            .callMethod(Clock.class, "system", ZoneId.class)
            .orShould()
            .callMethod(Clock.class, "tickMillis", ZoneId.class)
            .orShould()
            .callMethod(Clock.class, "tickSeconds", ZoneId.class)
            .orShould()
            .callMethod(Clock.class, "tickMinutes", ZoneId.class)
            .orShould()
            .callMethod(UUID.class, "randomUUID")
            .orShould()
            .callMethod(Math.class, "random")
            .orShould()
            .callMethod(ThreadLocalRandom.class, "current")
            .orShould()
            .callConstructor(Random.class)
            .orShould()
            .callConstructor(Random.class, long.class)
            .orShould()
            .callConstructor(SplittableRandom.class)
            .orShould()
            .callConstructor(SplittableRandom.class, long.class)
            .orShould()
            .callConstructor(SecureRandom.class)
            .orShould()
            .callMethod(SecureRandom.class, "getInstance", String.class)
            .orShould()
            .callMethod(SecureRandom.class, "getInstance", String.class, String.class)
            .orShould()
            .callMethod(SecureRandom.class, "getInstanceStrong")
            .orShould()
            .callMethod(RandomGenerator.class, "getDefault")
            .orShould()
            .callMethod(RandomGenerator.class, "of", String.class)
            .orShould()
            .callMethod(RandomGeneratorFactory.class, "of", String.class)
            .because("时间与随机必须经 Clock / IdGenerator 注入；带参的 now(clock) 与 Clock.fixed 不受限，"
                    + "否则测试只能靠 sleep 与模糊断言。`Clock.system*` 要单独禁：`Instant.now(Clock.systemUTC())` "
                    + "能一行绕过 `Instant.now()` 这条禁令。"
                    // 单参 callMethod / callConstructor 是**按参数表精确相等**匹配（空 varargs 只等于无参），
                    // 不是按方法名匹配：实测 `callMethod(Instant.class, "now")` 只报无参调用，
                    // `Instant.now(Clock.systemUTC())` 由 `Clock.systemUTC` 那条单独兜住。反过来说，
                    // 想禁「带参的那个」也必须把参数类型写出来，否则一条都禁不到。
                    + "`SecureRandom.getInstance(String)` 与 `getInstanceStrong()` 是与 `new SecureRandom()` 同类的"
                    + "环境熵源，`RandomGenerator.getDefault()` / `RandomGenerator.of(String)` /"
                    + "`RandomGeneratorFactory.of(String)` 是同一族工厂方法，一并禁掉。"
                    // 带 ZoneId 的 `now(zone)` 与 `Clock.tick*` 必须逐个点名：空 varargs 只等于「无参」，
                    // 写一条 `now` 禁不到 `now(ZoneId)`——那正是把 `Instant.now()` 洗成
                    // `Instant.now(Clock.systemDefaultZone())` 之外的另一条同款洗白路径（实测 11 条漏网）。
                    //
                    // **不**禁 `Random.from(RandomGenerator)`：它是把已注入的随机源适配成 `Random` 的
                    // 合法写法（JDK 17 的桥），禁掉就把注入式夹具判红了，与本文件上文同一个理由。
                    + "带 ZoneId 的 `now(zone)` 与 `Clock.tick*` 也要禁：它们读的是同一只系统钟，"
                    + "不写进参数表就等于留了 11 条一行洗白路径。`Random.from(RandomGenerator)` 不在此列——"
                    + "它是把**已注入**的随机源适配成 `Random` 的桥，禁掉就会把注入式夹具判红");

    /// 禁止静态可变状态。
    @ArchTest
    static final ArchRule NO_MUTABLE_STATIC_STATE =
            noFields().that().areStatic().should().notBeFinal().because("静态可变字段是隐藏的全局状态，会让测试互相污染、让并发行为不可复现");

    /// 公开接口保持窄。
    ///
    /// `api` 现有 29 个公开接口，规则常驻生效。不设 `allowEmptyShould(true)`：
    /// 若某天规则匹配不到任何接口（包名漂移、过滤器被改坏、`api` 被误删），
    /// ArchUnit 会直接失败，那正是应该红的时候。
    @ArchTest
    static final ArchRule API_INTERFACES_STAY_NARROW = classes()
            .that()
            .areInterfaces()
            .and()
            .resideInAPackage("io.github.nextentity.api..")
            .should(haveAtMostPublicMethods())
            .because("接口方法超过 6 个说明边界切错了；应拆成两个窄接口，而不是加第 7 个方法");

    /// `api` 是契约层：不得反向依赖任何内部模块。
    ///
    /// 模块循环规则只能抓住「互相依赖」；单向的 api → core 会静默通过，所以单独守一条。
    @ArchTest
    static final ArchRule API_DEPENDS_ON_NOTHING_INTERNAL = noClasses()
            .that()
            .resideInAPackage("io.github.nextentity.api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.github.nextentity.core..", "io.github.nextentity.jpa..", "io.github.nextentity.spring..")
            .allowEmptyShould(true)
            .because("api ← core ← jpa ← spring 只能单向；api 反向依赖会让契约层被实现细节绑死");

    /// `core` 只放与后端无关的抽象：不得依赖 `jpa` / `spring` / `jdbc`。
    @ArchTest
    static final ArchRule CORE_DOES_NOT_DEPEND_ON_BACKENDS = noClasses()
            .that()
            .resideInAPackage("io.github.nextentity.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.github.nextentity.jpa..", "io.github.nextentity.spring..", "io.github.nextentity.jdbc..")
            .allowEmptyShould(true)
            .because("Compiler / Executor 是接缝，接缝自己引用实现就是循环的开始");

    /// `jdbc` 是实现侧后端：只许依赖 `core` 接缝、`jakarta.persistence` 注解与 JDK。
    ///
    /// 与上一条互为反向：那条守「core 不得引用实现」，这条守「实现不得回头依赖别的实现，
    /// 也不得把测试基座拖进生产代码」。`jpa` 侧由 enforcer 的 `bannedDependencies` 与模块
    /// 依赖图共同守着，`jdbc` 目前只有本规则显式点名 testing。
    ///
    /// 不设 `allowEmptyShould(true)`：本模块 pom 已依赖 `nextentity-jdbc`，规则必须真能匹配到
    /// 该包的类（理由同 `TESTING_STAYS_BACKEND_FREE`）。规则一旦匹配空集，要么是依赖被删、
    /// 要么是包名漂移——两种都该红，而不是静默绿。
    @ArchTest
    static final ArchRule JDBC_IS_A_BACKEND_ONLY = noClasses()
            .that()
            .resideInAPackage("io.github.nextentity.jdbc..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.github.nextentity.jpa..",
                    "io.github.nextentity.spring..",
                    "io.github.nextentity.testing..",
                    "org.hibernate..",
                    "org.springframework..")
            .because("jdbc 是 core 接缝的第二实现：反向依赖 jpa/spring 即循环，依赖 testing 即把测试基座拖进生产代码");

    /// `spring` 只做装配：不得构建 Criteria。
    @ArchTest
    static final ArchRule SPRING_HAS_NO_QUERY_LOGIC = noClasses()
            .that()
            .resideInAPackage("io.github.nextentity.spring..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("jakarta.persistence.criteria..")
            .allowEmptyShould(true)
            .because("TEAM-CONTRACT §3：Spring 装配与属性归 spring，查询逻辑一律不进 spring");

    /// 一致性测试基类不得被某个后端绑架：`testing` 只准依赖 `api` + `core` 接口。
    ///
    /// 顺带说明它守不住的那一半：这条规则按类型依赖执法，能挡住基类引用
    /// `java.sql..` / `jakarta..` / `org.hibernate..` / `org.springframework..`；
    /// 但「基类里拿一个 `String` 拼 SQL 再断言」没有任何类型特征，ArchUnit 看不到数据流，
    /// 那一半只能靠评审（DESIGN §5「编写约定」）兜底，不要再堆一条空转规则假装覆盖。
    ///
    /// 不设 `allowEmptyShould(true)`：本模块 pom 已依赖 `nextentity-testing`，规则必须真能匹配到
    /// 该包下的类。曾经缺这条依赖、靠 `allowEmptyShould(true)` 静默变绿——一条匹配不到任何类的
    /// 门禁等于没有门禁，而它看上去是绿的，比没有更糟。包名漂移或依赖被删时直接红，才是该有的行为。
    @ArchTest
    static final ArchRule TESTING_STAYS_BACKEND_FREE = noClasses()
            .that()
            .resideInAPackage("io.github.nextentity.testing..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta..", "org.hibernate..", "org.springframework..", "java.sql..")
            .because("DESIGN §5：基类里出现 EntityManager / Criteria / SQL，新后端就得改基类，接缝随之失效");

    /// 生产代码不得依赖 mock 框架或测试框架。
    ///
    /// 守的是什么：`api` / `core` / `jpa` / `spring` 的 `src/main` 里一旦出现
    /// `org.mockito..` / `org.easymock..` / `org.powermock..` / `org.junit..` / `org.assertj..` /
    /// `com.tngtech.archunit..` 的任何引用即违规。mock 只能证明「调用形状对」
    /// （AGENTS「mock 只许证形状」），它漏进生产代码就等于线上路径跑在假对象上。
    ///
    /// 为什么现在抓：enforcer 的 `bannedDependencies` 只禁了 core 的 `jakarta` / `hibernate` / `spring`
    /// 与 jpa 的 `spring`，没有任何一条禁令阻止把 `mockito-core` 声明成 compile 依赖再 `import` 进
    /// `core` / `jpa` / `spring`；而 `nextentity-testing` 整模块的 main 代码都是 JUnit + AssertJ，
    /// 把它那套写法抄进生产模块的成本极低，编译期与 enforcer 都不会出声。
    ///
    /// 为什么逐包列四个生产模块、而不写 `io.github.nextentity..`：`nextentity-testing` 的 main 代码
    /// 合法地依赖 JUnit / AssertJ（它本身就是测试基座）。用通配包名会把它一起判红——一旦它被加进
    /// 本模块 classpath（修好 `TESTING_STAYS_BACKEND_FREE` 的正确做法），这条规则就会假红。
    ///
    /// 测试代码不在范围内：本类用 `DoNotIncludeTests` 导入，天然只看生产代码。测试模块要 mock
    /// 什么是它们的自由（`nextentity-spring` 现在连 `mockito-core` 都不在 classpath 上了，
    /// 自动配置用例改用真 Hibernate + H2，见模块 pom 的说明），与这条规则无关。
    @ArchTest
    static final ArchRule PRODUCTION_CODE_IS_FREE_OF_TEST_FRAMEWORKS = noClasses()
            .that()
            .resideInAnyPackage(
                    "io.github.nextentity.api..",
                    "io.github.nextentity.core..",
                    "io.github.nextentity.jpa..",
                    "io.github.nextentity.jdbc..",
                    "io.github.nextentity.spring..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.mockito..",
                    "org.easymock..",
                    "org.powermock..",
                    "org.junit..",
                    "org.assertj..",
                    "com.tngtech.archunit..")
            .because("生产代码依赖 mock / 测试框架 = 线上路径跑在假对象上；enforcer 的 bannedDependencies 没有这条禁令");

    /// `api` 包的 class 只能来自 `nextentity-api` 自己的产物。
    ///
    /// 这是构建污染门禁：`.m2` 里曾出现打过 api class 的 `nextentity-core` jar（`api/QueryLock.class`），
    /// classpath 顺序一旦让 core 排在 api 之前，编译器就会用上那份过期签名，报出与源码无关的假故障。
    /// enforcer 与包名规则都看不出「同一个类被两个产物各打一份」，只有来源 URI 能。
    @ArchTest
    static final ArchRule API_PACKAGE_LIVES_IN_API_MODULE = classes()
            .that()
            .resideInAPackage("io.github.nextentity.api..")
            .should(new ArchCondition<>("只能来自 nextentity-api 产物") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    String source =
                            item.getSource().map(s -> s.getUri().toString()).orElse("<来源未知>");
                    if (!source.contains("nextentity-api")) {
                        events.add(SimpleConditionEvent.violated(item, item.getName() + " 的来源是 " + source));
                    }
                }
            })
            .allowEmptyShould(true)
            .because("core/jpa/spring 的产物里出现 api 包 = 构建污染：classpath 顺序会让编译器用上过期的 api 签名");

    /// 公开方法（不含 static）不超过 `API_MAX_PUBLIC_METHODS` 个。
    ///
    /// 例外只走 `WIDE_INTERFACE_ALLOWLIST`，且上限是精确值：`Collector` 允许 7，第 8 个方法照样红。
    ///
    /// @return 条件
    private static ArchCondition<JavaClass> haveAtMostPublicMethods() {
        return new ArchCondition<>("声明不超过 " + API_MAX_PUBLIC_METHODS + " 个公开方法（例外见 WIDE_INTERFACE_ALLOWLIST）") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                int max = WIDE_INTERFACE_ALLOWLIST.getOrDefault(item.getName(), API_MAX_PUBLIC_METHODS);
                long count = item.getMethods().stream()
                        .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                        .filter(method -> !method.getModifiers().contains(JavaModifier.STATIC))
                        .count();
                if (count > max) {
                    events.add(
                            SimpleConditionEvent.violated(item, item.getName() + " 声明了 " + count + " 个公开方法，上限 " + max));
                }
            }
        };
    }
}
