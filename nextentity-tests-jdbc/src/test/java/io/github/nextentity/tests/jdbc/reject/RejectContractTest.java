package io.github.nextentity.tests.jdbc.reject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.tests.jdbc.AssetEntity;
import io.github.nextentity.tests.jdbc.JdbcAssetSupport;
import io.github.nextentity.tests.jdbc.OwnerEntity;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// 关联属性写入拒绝（§4.3 #21）：非 null 关联值必须抛 `UnsupportedOperationException`，不得静默置 null 丢数据；
/// 关联值为 null 时该列跳过，写入照常成功（§7 决策 4 的正向面）。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RejectContractTest {

    private final JdbcAssetSupport support = new JdbcAssetSupport("reject_contract_test");

    @BeforeEach
    void clear() {
        support.execute("delete from asset");
        support.execute("delete from owner");
    }

    @Test
    void associationWriteWithOwnerIsRejected() {
        AssetEntity asset = new AssetEntity(1L, "a", new OwnerEntity(1L, "o"));
        assertThatThrownBy(() -> support.operations().insert(asset))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("关联属性写入未支持: " + AssetEntity.class.getName() + ".owner");
    }

    @Test
    void associationWriteWithNullOwnerSkipsTheColumn() {
        support.operations().insert(new AssetEntity(2L, "b", null));
        List<AssetEntity> rows = support.query().list();
        assertThat(rows).extracting(AssetEntity::name).containsExactly("b");
        assertThat(rows.get(0).owner()).isNull();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
