package io.github.nextentity.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `TypeCastUtil.cast(Class)` 的行为：point-mall `CsvHandler.getRequestClass()` 依赖它做受检转型。
@DisplayName("TypeCastUtil")
class TypeCastUtilTest {

    @Test
    @DisplayName("cast 返回同一个 Class 对象")
    void castReturnsSameClass() {
        Class<?> wildcard = String.class;

        Class<String> casted = TypeCastUtil.cast(wildcard);

        assertThat(casted).isSameAs(String.class);
    }
}
