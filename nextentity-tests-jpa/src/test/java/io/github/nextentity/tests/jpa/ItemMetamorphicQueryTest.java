package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.MetamorphicQueryTest;
import org.junit.jupiter.api.AfterEach;

/// 把**变形关系**（TLP / 双重取反 / 分页重组）用例接到 JPA 后端（H2）上：用例本体在
/// `nextentity-testing` 模块，本类只做接线。
///
/// 变形用例的期望值不是手算常量，而是另一条查询的结果，因此本类不提供任何期望值；
/// 换后端不需要改用例，这也是「跨方言对拍」能直接复用的原因。
class ItemMetamorphicQueryTest extends MetamorphicQueryTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_metamorphic_query");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
