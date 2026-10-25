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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/// 架构门禁：依赖方向、包边界与可测试性约束由测试守住，而不是靠自觉。
///
/// 本模块只有测试代码，随 `mvn verify` 与 `mvn test` 一起执行。独立成模块的原因：
/// ArchUnit 只能看到 classpath 上的类，规则放在 `nextentity-core` 里就永远看不到
/// `nextentity-jpa` / `nextentity-spring`，跨模块约束会静默失效。
@AnalyzeClasses(packages = "io.github.nextentity", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /// `api` 接口的公开方法数上限（AGENTS「接口要窄」）。
    private static final int API_MAX_PUBLIC_METHODS = 6;

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
    /// 随机源这条**不用** `callConstructor(Random.class)`：ArchUnit 的单参重载只匹配
    /// **无参**构造器，`new Random(seed)` 会静默漏过（1.4.1 实测）。改成「依赖了
    /// `RandomGenerator` 及其任何实现」——`Random` / `SecureRandom` / `SplittableRandom`
    /// 一次全盖，也不再依赖构造器签名。
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
            .callMethod(LocalDateTime.class, "now")
            .orShould()
            .callMethod(UUID.class, "randomUUID")
            .orShould()
            .callMethod(Math.class, "random")
            .orShould()
            .callMethod(ThreadLocalRandom.class, "current")
            .orShould()
            .dependOnClassesThat()
            .areAssignableTo(RandomGenerator.class)
            .because("时间与随机必须经 Clock / IdGenerator 注入；带参的 now(clock) 不受限，否则测试只能靠 sleep 与模糊断言");

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

    /// `core` 只放与后端无关的抽象：不得依赖 `jpa` / `spring`。
    @ArchTest
    static final ArchRule CORE_DOES_NOT_DEPEND_ON_BACKENDS = noClasses()
            .that()
            .resideInAPackage("io.github.nextentity.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.github.nextentity.jpa..", "io.github.nextentity.spring..")
            .allowEmptyShould(true)
            .because("Compiler / Executor 是接缝，接缝自己引用实现就是循环的开始");

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
    /// @return 条件
    private static ArchCondition<JavaClass> haveAtMostPublicMethods() {
        int max = API_MAX_PUBLIC_METHODS;
        return new ArchCondition<>("声明不超过 " + max + " 个公开方法") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
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
