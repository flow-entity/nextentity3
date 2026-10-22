package io.github.nextentity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.entity.InstantEntity;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

/// 时间字面量端到端测试：JVM 默认时区不是 UTC 时，Instant 条件仍能命中已写入的行。
///
/// 这条链路覆盖的是参数绑定的类型解析与取值：条件值经 `cb.parameter(Instant.class)` 创建，
/// 再由 `TypedQuery#setParameter` 下发，Hibernate 按 TIMESTAMP_UTC 处理该值。
///
/// H2 接受 setObject(Instant, TIMESTAMP_WITH_TIMEZONE) 并按会话时区转换写入值，
/// 绑定与内联两条路径在 H2 上口径一致，所以时区偏差本身要在 MySQL/PostgreSQL/SQL Server 上才会暴露。
@DisplayName("时间字面量端到端测试")
public class InstantLiteralTimezoneIntegrationTest {

    private static final long ID = 9001L;

    private static final Instant GRANT_TIME_END = Instant.parse("2026-10-12T21:27:07Z");

    private final TimeZone originalTimeZone = TimeZone.getDefault();

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(originalTimeZone);
        IntegrationTestContext context = IntegrationTestProvider.getEntityManagerContext();
        if (context != null) {
            context.reset();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("JVM 默认时区偏离 UTC 时 Instant 条件仍应命中已写入的行")
    void shouldMatchInstantConditionUnderNonUtcDefaultZone(IntegrationTestContext context) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        // 2.2.3 用条件删除先清掉同 ID 的历史行；3.x 没有条件删除，改为「查出来再删」，
        // 语义相同：进入断言前表里不能有这个 ID 的残留行。
        InstantEntity existing = context.operations(InstantEntity.class)
                .query()
                .where(InstantEntity::getId)
                .eq(ID)
                .first();
        if (existing != null) {
            context.delete(existing);
        }

        context.operations(InstantEntity.class).insert(new InstantEntity(ID, "grant", GRANT_TIME_END));

        List<InstantEntity> found = context.operations(InstantEntity.class)
                .query()
                .where(InstantEntity::getCreatedAt)
                .ge(GRANT_TIME_END)
                .list();

        assertThat(found).extracting(InstantEntity::getId).containsExactly(ID);
    }
}
