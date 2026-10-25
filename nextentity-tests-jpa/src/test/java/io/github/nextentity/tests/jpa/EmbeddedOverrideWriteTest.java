package io.github.nextentity.tests.jpa;

import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// 嵌入写侧 + `@AttributeOverride` 的 H2 通道：断言全部继承自 [EmbeddedOverrideWriteContract]，
/// 本类只接 H2 内存库（MySQL 兼容模式）并在类结束时关掉装置。
///
/// `PER_CLASS` 是刻意的：一个类只起一套 `SessionFactory`，`@AfterAll` 才拿得到实例字段；
/// 若用默认的 per-method 生命周期，子类的 `@AfterEach` 会**先于**基类的复位钩子执行，
/// 复位就会打在已关闭的装置上。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddedOverrideWriteTest extends EmbeddedOverrideWriteContract {

    private final WriteRoundTripSupport<ContactItemEntity> support =
            WriteRoundTripSupport.onH2("embedded_override_write", ContactItemEntity.class);

    @Override
    protected WriteRoundTripSupport<ContactItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
