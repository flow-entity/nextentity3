package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/// 证明 JPA 侧夹具可用：`JpaPersistExecutor` 写入 + 清空 + 回读，且每次 seed 是幂等的。
///
/// 这是夹具自检，不是能力一致性用例（那些由 `nextentity-testing` 的抽象基类提供）。
class ItemFixtureTest {

    private static final ItemEntity APPLE = new ItemEntity(1L, "apple", 10, null);
    private static final ItemEntity BANANA = new ItemEntity(2L, "banana", 20, "n1");

    @Test
    void seedsAndReadsBack() {
        try (ItemFixture fixture = ItemFixture.start("item_fixture_self_test")) {
            fixture.seed(List.of(APPLE, BANANA));

            List<ItemEntity> rows = fixture.entityManager()
                    .createQuery("select i from ItemEntity i order by i.id", ItemEntity.class)
                    .getResultList();

            assertThat(rows).extracting(ItemEntity::id).containsExactly(1L, 2L);
            assertThat(rows).extracting(ItemEntity::name).containsExactly("apple", "banana");
        }
    }

    @Test
    void seedReplacesPreviousState() {
        try (ItemFixture fixture = ItemFixture.start("item_fixture_replace_test")) {
            fixture.seed(List.of(APPLE, BANANA));
            fixture.seed(List.of(new ItemEntity(3L, "cherry", 30, "n2")));

            Long count = fixture.entityManager()
                    .createQuery("select count(i) from ItemEntity i", Long.class)
                    .getSingleResult();

            assertThat(count).isEqualTo(1L);
        }
    }
}
