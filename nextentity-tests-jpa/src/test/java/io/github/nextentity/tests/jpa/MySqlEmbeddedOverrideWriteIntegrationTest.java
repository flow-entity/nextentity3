package io.github.nextentity.tests.jpa;

import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 嵌入写侧 + `@AttributeOverride` 的真方言版：与 [EmbeddedOverrideWriteTest] 同一套断言，只换连接。
///
/// 真方言上多出的风险正是这条用例存在的理由：
/// - `@AttributeOverride` 换出的列名（`home_city` / `home_zone` …）由 hbm2ddl 按真 MySQL 的类型规则建列，
///   H2 的 MySQL 兼容模式对「嵌套覆盖路径 `geo.zone`」是否照同样方式摊平并没有保证；
/// - `update` 把整块嵌入塞进 `CriteriaUpdate.set(...)` 后能否被真方言接受（Hibernate 摊平依赖方言能力），
///   只有在真库上执行过才算数。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlEmbeddedOverrideWriteIntegrationTest extends EmbeddedOverrideWriteContract {

    private final WriteRoundTripSupport<ContactItemEntity> support =
            WriteRoundTripSupport.onMySql(ContactItemEntity.class);

    @Override
    protected WriteRoundTripSupport<ContactItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
