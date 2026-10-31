package io.github.nextentity.tests.jdbc.phasec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.tests.jdbc.support.SingleConnectionDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// Phase C 独立端到端验证（**红**测试）：JDBC 实体写路径是否按关联目标 `@Id` 的列映射绑定外键与 WHERE 主键。
///
/// 缺陷机制（[io.github.nextentity.jdbc.exec.WriteSql]）：
/// - 标量列走 `JdbcTypes.toJdbc(值, 列)`（套 `@Convert`）；
/// - 外键列却直接绑 `JdbcEntityAttribute.associatedId(...)` 的裸值（`associationColumn`）；
/// - `update(entity)` 的 `where id = ?` 也直接绑主键字段原值。
///
/// 输出形态因此与查询侧（`SqlPaths` 用 `foreignKey(...)` 复用目标 `@Id` 的列映射）不一致：
/// 写进库的是 H2 隐式转换后的 `"9"`，而比较时绑的是转换后的 `"owner-9"`。
///
/// 夹具刻意用**非恒等**映射（`9L ↔ "owner-9"`）：恒等的 `toString` 型映射会被 H2 的隐式
/// 数值→字符转换掩盖（落库 `"9"` 恰好等于转换结果），测不到缺陷。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConvertedIdAssociationWriteTest {

    private static final String CREATE_OWNERS =
            "create table if not exists converted_owner (id varchar(64) primary key, name varchar(64))";
    private static final String CREATE_ASSETS = "create table if not exists converted_id_asset"
            + " (id bigint primary key, name varchar(64), owner_id varchar(64))";

    private final SingleConnectionDataSource dataSource = SingleConnectionDataSource.h2("phasec_converted_ids");
    private final EntityOperations<ConvertedOwnerEntity> owners =
            new JdbcEntityOperationsFactory(dataSource).operations(ConvertedOwnerEntity.class);
    private final EntityOperations<ConvertedIdAssetEntity> assets =
            new JdbcEntityOperationsFactory(dataSource).operations(ConvertedIdAssetEntity.class);

    @BeforeEach
    void reset() {
        execute(CREATE_OWNERS);
        execute(CREATE_ASSETS);
        execute("delete from converted_id_asset");
        execute("delete from converted_owner");
    }

    @AfterAll
    void closeDataSource() {
        dataSource.close();
    }

    /// 写入外键列时必须套用目标 `@Id` 的列映射：库里应当是 `"owner-9"`，不是 `"9"`。
    @Test
    void insertWritesForeignKeyWithTargetIdColumnMapping() {
        owners.insert(new ConvertedOwnerEntity(9L, "acme"));
        assets.insert(new ConvertedIdAssetEntity(1L, "laptop", new ConvertedOwnerEntity(9L, "acme")));

        assertThat(rawForeignKey(1L)).isEqualTo("owner-9");
    }

    /// 写入后按 `关联.id` 查回来必须命中：查询侧已按列映射绑 `"owner-9"`，写侧写的是裸 `9L` 的隐式形态。
    @Test
    void queryByAssociationIdFindsInsertedRow() {
        owners.insert(new ConvertedOwnerEntity(9L, "acme"));
        assets.insert(new ConvertedIdAssetEntity(1L, "laptop", new ConvertedOwnerEntity(9L, "acme")));

        List<ConvertedIdAssetEntity> found = assets.query()
                .where(EntityPath.of(ConvertedIdAssetEntity::owner).get(ConvertedOwnerEntity::id))
                .eq(9L)
                .list();

        assertThat(found).extracting(ConvertedIdAssetEntity::id).containsExactly(1L);
    }

    /// `update(entity)` 的 `where id = ?` 同样要按列映射绑定，否则 0 行受影响（抛 `EntityNotFoundException`）。
    @Test
    void updateEntityMatchesRowByConvertedId() {
        owners.insert(new ConvertedOwnerEntity(9L, "acme"));

        owners.update(new ConvertedOwnerEntity(9L, "acme-renamed"));

        assertThat(rawOwnerName(9L)).isEqualTo("acme-renamed");
    }

    private String rawForeignKey(long assetId) {
        try (PreparedStatement statement =
                dataSource.getConnection().prepareStatement("select owner_id from converted_id_asset where id = ?")) {
            statement.setLong(1, assetId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String rawOwnerName(long ownerId) {
        try (PreparedStatement statement =
                dataSource.getConnection().prepareStatement("select name from converted_owner where id = ?")) {
            statement.setString(1, new OwnerIdColumnConverter().convertToDatabaseColumn(ownerId));
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("执行 DDL/DML 失败: " + sql, e);
        }
    }
}
