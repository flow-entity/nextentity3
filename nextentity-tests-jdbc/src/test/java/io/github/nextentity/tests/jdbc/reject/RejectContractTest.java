package io.github.nextentity.tests.jdbc.reject;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.tests.jdbc.AssetEntity;
import io.github.nextentity.tests.jdbc.JdbcAssetSupport;
import io.github.nextentity.tests.jdbc.OwnerEntity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// 关联属性写入（§4.3 #21 / §7.1 CUT-17）：非 null 关联值从关联对象的 `@Id` 取外键列写入，不再抛
/// `UnsupportedOperationException`；关联值为 null 时该列跳过，写入照常成功。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RejectContractTest {

    private final JdbcAssetSupport support = new JdbcAssetSupport("reject_contract_test");

    @BeforeEach
    void clear() {
        support.execute("delete from asset");
        support.execute("delete from owner");
    }

    @Test
    void associationWriteUsesReferencedIdAsForeignKey() throws SQLException {
        support.execute("insert into owner (id, name) values (1, 'o')");

        support.operations().insert(new AssetEntity(1L, "a", new OwnerEntity(1L, "o")));

        try (Statement statement = support.dataSource().connection().createStatement();
                ResultSet rows = statement.executeQuery("select owner_id from asset where id = 1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isEqualTo(1L);
        }
        assertThat(support.query().list()).extracting(AssetEntity::name).containsExactly("a");
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
