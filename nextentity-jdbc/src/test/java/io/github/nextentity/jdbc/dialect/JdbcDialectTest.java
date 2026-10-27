package io.github.nextentity.jdbc.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/// 方言探测（§6、§4.3 #20）：只按 `DatabaseMetaData.getDatabaseProductName()` 匹配 MySQL / H2，
/// 大小写无关；未知产品名一律拒绝，不返回默认方言。
class JdbcDialectTest {

    @Test
    void productNameMatchingIsCaseInsensitive() {
        assertThat(JdbcDialect.of("MySQL").name()).isEqualTo("mysql");
        assertThat(JdbcDialect.of("mysql").name()).isEqualTo("mysql");
        assertThat(JdbcDialect.of("H2").name()).isEqualTo("h2");
        assertThat(JdbcDialect.of("h2").name()).isEqualTo("h2");
    }

    @Test
    void unknownProductNameIsRejected() {
        for (String product : List.of("Oracle", "PostgreSQL", "MariaDB")) {
            assertThatThrownBy(() -> JdbcDialect.of(product))
                    .as("产品名 %s 必须拒绝", product)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("不支持的数据库产品: " + product + "（本期只支持 MySQL / H2）");
        }
    }

    @Test
    void nullProductNameIsRejectedWithLiteralNull() {
        assertThatThrownBy(() -> JdbcDialect.of(null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("不支持的数据库产品: null（本期只支持 MySQL / H2）");
    }
}
