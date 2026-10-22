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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/// 架构门禁：依赖方向、包边界与可测试性约束由测试守住，而不是靠自觉。
///
/// 本模块只有测试代码，随 `mvn verify` 与 `mvn test` 一起执行。独立成模块的原因：
/// ArchUnit 只能看到 classpath 上的类，规则放在 `nextentity-core` 里就永远看不到
/// `nextentity-jpa` / `nextentity-spring`，跨模块约束会静默失效。
@AnalyzeClasses(packages = "io.github.nextentity", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

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
            .callConstructor(Random.class)
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
            .should(haveAtMostPublicMethods(6))
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
    @ArchTest
    static final ArchRule TESTING_STAYS_BACKEND_FREE = noClasses()
            .that()
            .resideInAPackage("io.github.nextentity.testing..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta..", "org.hibernate..", "org.springframework..", "java.sql..")
            .allowEmptyShould(true)
            .because("DESIGN §5：基类里出现 EntityManager / Criteria / SQL，新后端就得改基类，接缝随之失效");

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

    /// 公开方法（不含 static）不超过给定数量。
    ///
    /// @param max 上限
    /// @return 条件
    private static ArchCondition<JavaClass> haveAtMostPublicMethods(int max) {
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
