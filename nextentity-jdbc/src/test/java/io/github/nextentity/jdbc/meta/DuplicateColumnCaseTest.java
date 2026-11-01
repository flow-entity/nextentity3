package io.github.nextentity.jdbc.meta;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 同列查重按「裸列名原文」比较：`code` 与 `CODE` 都没用反引号引用，在 H2 / MySQL / PostgreSQL / SQL Server
/// 上都落到**同一列**（H2 连 `create table t (code varchar(20), CODE varchar(20))` 都直接拒绝：
/// `Duplicate column name "CODE"`）。这类映射通过元数据期校验后，`insert` 渲染出
/// `insert into t (id, code, CODE) ...`、`update` 渲染两次 `code = ?`，
/// 运行期才由数据库以裸 `SQLException` 报错——正是查重想提前拦住的形态。
class DuplicateColumnCaseTest {

    @Test
    @DisplayName("仅大小写不同的两个列名在元数据期拒绝（未引用标识符不区分大小写）")
    void columnNamesDifferingOnlyInCaseAreRejected() {
        assertThatThrownBy(() -> new JdbcMeta().entityType(CaseDuplicate.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("列名重复");
    }

    @Test
    @DisplayName("对照：完全相同的列名已被拒绝（两种形态是同一列，判据必须一致）")
    void identicalColumnNamesAreRejected() {
        assertThatThrownBy(() -> new JdbcMeta().entityType(ExactDuplicate.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("列名重复");
    }

    @Entity
    @Table(name = "case_duplicate")
    static class CaseDuplicate {

        @Id
        Long id;

        @Column(name = "code")
        String first;

        @Column(name = "CODE")
        String second;

        protected CaseDuplicate() {}
    }

    @Entity
    @Table(name = "exact_duplicate")
    static class ExactDuplicate {

        @Id
        Long id;

        @Column(name = "code")
        String first;

        @Column(name = "code")
        String second;

        protected ExactDuplicate() {}
    }
}
