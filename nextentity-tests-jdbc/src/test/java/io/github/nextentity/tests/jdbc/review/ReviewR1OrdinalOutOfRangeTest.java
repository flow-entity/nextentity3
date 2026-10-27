package io.github.nextentity.tests.jdbc.review;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.tests.jdbc.JdbcItemSupport;
import io.github.nextentity.tests.jdbc.OrdinalItemEntity;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

/// 评审发现 R1：越界 ORDINAL 读回应按设计 §3.12 / §4.3 #24 包成 PersistenceException
/// （`SQL 执行失败: <sql>`），现状是裸 ArrayIndexOutOfBoundsException。
///
/// 复跑：`bash tmp/scripts/mvnl.sh -pl nextentity-tests-jdbc -am test -Dtest=ReviewR1OrdinalOutOfRangeTest`
class ReviewR1OrdinalOutOfRangeTest {

    @Test
    void outOfRangeOrdinalIsWrappedAsPersistenceException() {
        try (JdbcItemSupport support = new JdbcItemSupport("review_r1_ordinal")) {
            support.execute("insert into ordinal_item (id, status) values (1, 99)");

            var operations = new JdbcEntityOperationsFactory(support.dataSource()).operations(OrdinalItemEntity.class);

            assertThatThrownBy(() -> operations.query().list())
                    .as("ORDINAL 越界是行数据问题，必须按 #24 包成 PersistenceException 而不是泄漏 AIOOBE")
                    .isInstanceOf(PersistenceException.class)
                    .hasMessageContaining("SQL 执行失败");
        }
    }
}
