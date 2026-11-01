package io.github.nextentity.jdbc.meta;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `@Convert` 的两种非法形态必须得到同一个结论：两种写法都没有可用的转换器。
///
/// `JdbcColumns#converter` 对「`converter` 缺省且 `attributeName` 非空」抛
/// `@Convert 缺少 converter`，但对「两者都缺省」（裸 `@Convert`）直接返回 `null`——
/// 映射静默降级成「无转换器」，而 JPA 侧该注解此时没有任何含义（`converter` 与 `attributeName` 至少要给一个）。
class BareConvertRejectionTest {

    @Test
    @DisplayName("裸 @Convert（既无 converter 也无 attributeName）在元数据期拒绝")
    void bareConvertIsRejected() {
        assertThatThrownBy(() -> new JdbcMeta().entityType(BareConvert.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("@Convert");
    }

    @Test
    @DisplayName("对照：带 attributeName 缺 converter 的同形非法映射已被拒绝（两种写法的结论必须一致）")
    void convertWithAttributeNameButNoConverterIsRejected() {
        assertThatThrownBy(() -> new JdbcMeta().entityType(NamedConvert.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("@Convert 缺少 converter");
    }

    @Entity
    @Table(name = "bare_convert")
    static class BareConvert {

        @Id
        Long id;

        @Convert
        String label;

        protected BareConvert() {}
    }

    @Entity
    @Table(name = "named_convert")
    static class NamedConvert {

        @Id
        Long id;

        @Convert(attributeName = "label")
        String label;

        protected NamedConvert() {}
    }
}
