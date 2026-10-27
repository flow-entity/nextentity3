package io.github.nextentity.tests.jdbc.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/// 容器用例的开启条件：默认「没有 Docker 就跳过」，CI 可以要求它**不许静默跳过**。
///
/// 不用 `@Testcontainers(disabledWithoutDocker = true)` 的理由与 tests-jpa 相同：那种跳过在报表上
/// 与「用例通过」看不出区别，31 个对拍类会集体退化成 skip。选择权交给调用方：
/// 不设属性宽进跳过；`-Dnextentity.requireContainer=true` 时不跳过，Docker 缺失由容器启动失败打红。
public final class MySqlL4Gate implements ExecutionCondition {

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
