package io.github.nextentity.tests.jdbc.review;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.tests.jdbc.JdbcItemSupport;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

/// 评审发现 R3（Lead 裁决 2026-10-28）：只有 @Id 字段（没有可写标量列）的实体，`update` 不得渲染
/// `update ... set  where id=?` 这类非法 SQL，也不得 no-op 静默通过；必须在写入前抛
/// `UnsupportedOperationException`（拒绝条目与文案见 USAGE §10）。
///
/// 复跑：`mvn -pl nextentity-tests-jdbc -am test -Dtest=ReviewR3IdOnlyUpdateTest`
class ReviewR3IdOnlyUpdateTest {

    @Test
    void updateOnIdOnlyEntityIsRejectedBeforeSql() {
        try (JdbcItemSupport support = new JdbcItemSupport("review_r3_id_only")) {
            support.execute("create table review_id_only (id bigint primary key)");
            support.execute("insert into review_id_only (id) values (1)");

            var operations = new JdbcEntityOperationsFactory(support.dataSource()).operations(ReviewIdOnlyEntity.class);

            assertThatThrownBy(() -> operations.update(new ReviewIdOnlyEntity(1L)))
                    .as("没有可写标量列时必须在写入前拒绝（UOE），不得生成非法 SQL、也不得静默 no-op")
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Entity
    @Table(name = "review_id_only")
    static class ReviewIdOnlyEntity {
        @Id
        private Long id;

        protected ReviewIdOnlyEntity() {}

        ReviewIdOnlyEntity(Long id) {
            this.id = id;
        }

        Long id() {
            return id;
        }
    }
}
