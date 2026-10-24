package io.github.nextentity.tests.jpa.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/// L4 容器用例的开启条件：默认「没有 Docker 就跳过」，CI 可以要求它**不许静默跳过**。
///
/// 为什么不用 `@Testcontainers(disabledWithoutDocker = true)`：那个条件只会把用例「跳过」，
/// 于是没有 Docker 的机器上 110 条真方言对拍会悄悄变成 110 条 skip，构建照样绿——
/// L4 从「跑过了」退化成「没跑」，而报表上看不出区别。这里把选择权交给调用方：
///
/// - 不设属性：宽进，跳过（本地开发没有 Docker 也能跑完整套 L2/L3）；
/// - `-Dnextentity.requireContainer=true`：Docker 缺失时**不跳过**，让容器在静态初始化时
///   启动失败、构建变红。CI 应当始终带上这个属性，否则 L4 是纸面门禁。
public final class MySqlL4Gate implements ExecutionCondition {

    /// 强制要求的开关属性名。
    public static final String REQUIRE_PROPERTY = "nextentity.requireContainer";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (Boolean.getBoolean(REQUIRE_PROPERTY)) {
            return ConditionEvaluationResult.enabled("-D" + REQUIRE_PROPERTY + "=true：容器不可用时按失败处理，不允许静默跳过");
        }
        return DockerClientFactory.instance().isDockerAvailable()
                ? ConditionEvaluationResult.enabled("Docker 可用，运行容器用例")
                : ConditionEvaluationResult.disabled("Docker 不可用，跳过容器用例（要强制失败请加 -D" + REQUIRE_PROPERTY + "=true）");
    }
}
