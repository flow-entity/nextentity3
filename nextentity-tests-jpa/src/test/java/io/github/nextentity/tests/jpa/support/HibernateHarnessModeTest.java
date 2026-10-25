package io.github.nextentity.tests.jpa.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.testing.NullOrdering;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// 方言开关的**纯函数**部分：URL 形状、属性、取值校验、NULL 序映射。
///
/// 为什么这些必须有独立用例：整套「方言矩阵」的证据力全押在这几行上，而它们的错误形态都是
/// **默认通道照样全绿**——
///
/// - `MODE=PostgreSQL` 写错成 `MODE=Postgres`：H2 不认，退化成 H2 原生语义，PG 模式跑出来的是
///   第三种方言，却仍然「跑完了」；
/// - 删掉 `DEFAULT_NULL_ORDERING=HIGH`：H2 在 PG 模式下仍按 `NULLS FIRST` 排，排序差分会在
///   `NULLS_LAST` 的 oracle 上假红，而人会去怀疑被测代码；
/// - `DATABASE_TO_LOWER=TRUE` 丢掉：PG 里未加引号的标识符折成小写，H2 不折 → 大小写敏感的
///   列名在两边表现不同；
/// - `dialectProperties` 漏掉 `PostgreSQLDialect`：H2 用 PG 兼容语法、Hibernate 用 MySQL 方言渲染，
///   两边错位，报错位置离真正原因很远。
///
/// 默认通道（不带 `-Dnextentity.h2mode`）走 `MYSQL` 分支，因此这里同时钉住「MySQL 的 URL 逐字节
/// 就是历史那一条」，防止改这段代码时把默认通道悄悄换了库。
class HibernateHarnessModeTest {

    private static final String DATABASE = "mode-test";

    /// 默认模式：URL 与历史版本逐字节一致（`MODE=MySQL` + `DB_CLOSE_DELAY=-1`，不多不少）。
    @Test
    void mysqlModeKeepsTheHistoricalUrl() {
        assertThat(HibernateHarness.jdbcUrl(HibernateHarness.H2Mode.MYSQL, DATABASE))
                .isEqualTo("jdbc:h2:mem:mode-test;MODE=MySQL;DB_CLOSE_DELAY=-1");
    }

    /// PG 模式：三个开关缺一不可，缺任何一个都会让这一档证据失真（见类注释）。
    @Test
    void postgresqlModeTurnsOnAllThreeCompatibilitySwitches() {
        assertThat(HibernateHarness.jdbcUrl(HibernateHarness.H2Mode.POSTGRESQL, DATABASE))
                .isEqualTo("jdbc:h2:mem:mode-test"
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
    }

    /// 方言只在 PG 模式下钉死；MySQL 模式不许额外设属性（设了默认通道就不是历史那条路了）。
    @Test
    void dialectIsPinnedOnlyForPostgresql() {
        assertThat(HibernateHarness.dialectProperties(HibernateHarness.H2Mode.MYSQL))
                .isEmpty();
        assertThat(HibernateHarness.dialectProperties(HibernateHarness.H2Mode.POSTGRESQL))
                .containsExactly(entry("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"));
    }

    /// 取值校验：缺省/空白 → MySQL；大小写与前后空白宽容；未知取值直接失败而不是静默回退。
    @Test
    void resolveModeFailsClosedOnUnknownValues() {
        assertThat(HibernateHarness.resolveMode(null)).isEqualTo(HibernateHarness.H2Mode.MYSQL);
        assertThat(HibernateHarness.resolveMode("  ")).isEqualTo(HibernateHarness.H2Mode.MYSQL);
        assertThat(HibernateHarness.resolveMode("MySQL")).isEqualTo(HibernateHarness.H2Mode.MYSQL);
        assertThat(HibernateHarness.resolveMode(" PostgreSQL ")).isEqualTo(HibernateHarness.H2Mode.POSTGRESQL);
        assertThatThrownBy(() -> HibernateHarness.resolveMode("postgre"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextentity.h2mode")
                .hasMessageContaining("postgre");
    }

    /// NULL 序是方言事实：MySQL 升序把 `null` 排前，PG 排后。两个族的 oracle 都读这一处。
    @Test
    void nullOrderingFollowsTheDialect() {
        assertThat(HibernateHarness.nullOrdering(HibernateHarness.H2Mode.MYSQL)).isEqualTo(NullOrdering.NULLS_FIRST);
        assertThat(HibernateHarness.nullOrdering(HibernateHarness.H2Mode.POSTGRESQL))
                .isEqualTo(NullOrdering.NULLS_LAST);
    }

    private static Map.Entry<String, String> entry(String key, String value) {
        return Map.entry(key, value);
    }
}
