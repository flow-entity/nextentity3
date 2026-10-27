package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.GeneratedItem;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import io.github.nextentity.jdbc.support.VersionedItem;
import org.junit.jupiter.api.Test;

/// 写入语句快照（§7.5 写入 3 条 + §3.11）：直接断言包内 `WriteSql` 的整句与绑定值——
/// 这条用例在本包（`io.github.nextentity.jdbc.exec`）里，`WriteSql` 不进 public 面。
///
/// 列序/SET 列序/`where` 片段都是契约：`GeneratedIdInsertContract` 的 `rowCount` 分支和
/// tests-jpa 的写回契约都依赖「`set` 与 `where` 之间是 `name=?`」这种无空格形态（§3.11 冻结）。
class WriteSqlTest {

    private final JdbcMeta meta = new JdbcMeta();

    @Test
    void insertWithoutGeneratedKeyListsEveryScalarColumn() {
        SnapshotItem item = new SnapshotItem(7L, "banana", 10, 20, "n1", ItemStatus.ACTIVE);

        SqlStatement statement = WriteSql.insert(meta.entityType(SnapshotItem.class), item);

        assertThat(statement.sql())
                .isEqualTo("insert into item (id, name, score, score2, note, status) values (?, ?, ?, ?, ?, ?)");
        assertThat(statement.parameters()).containsExactly(7L, "banana", 10, 20, "n1", "ACTIVE");
    }

    @Test
    void insertSkipsGeneratedKeyColumn() {
        GeneratedItem item = new GeneratedItem("generated");

        SqlStatement statement = WriteSql.insert(meta.entityType(GeneratedItem.class), item);

        assertThat(statement.sql()).isEqualTo("insert into generated_item (name) values (?)");
        assertThat(statement.parameters()).containsExactly("generated");
    }

    @Test
    void insertSkipsNullAssociationColumn() {
        SnapshotAsset asset = new SnapshotAsset(3L, "bandage", null);

        SqlStatement statement = WriteSql.insert(meta.entityType(SnapshotAsset.class), asset);

        assertThat(statement.sql()).isEqualTo("insert into asset (id, name) values (?, ?)");
        assertThat(statement.parameters()).containsExactly(3L, "bandage");
    }

    @Test
    void insertRejectsNonNullAssociation() {
        SnapshotAsset asset = new SnapshotAsset(3L, "anvil", new SnapshotOwner(1L, "alpha"));

        assertThatThrownBy(() -> WriteSql.insert(meta.entityType(SnapshotAsset.class), asset))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("关联属性写入未支持: " + SnapshotAsset.class.getName() + ".owner");
    }

    @Test
    void updateSetsScalarColumnsAndMatchesId() {
        SnapshotItem item = new SnapshotItem(7L, "banana", 10, 20, "n1", ItemStatus.DONE);

        SqlStatement statement = WriteSql.update(meta.entityType(SnapshotItem.class), item);

        assertThat(statement.sql()).isEqualTo("update item set name=?, score=?, score2=?, note=?, status=? where id=?");
        assertThat(statement.parameters()).containsExactly("banana", 10, 20, "n1", "DONE", 7L);
    }

    @Test
    void updateAppendsVersionInSetAndWhere() {
        VersionedItem item = new VersionedItem(7L, "banana");
        item.version(2L);

        SqlStatement statement = WriteSql.update(meta.entityType(VersionedItem.class), item);

        assertThat(statement.sql()).isEqualTo("update versioned_item set name=?, version=? where id=? and version=?");
        assertThat(statement.parameters()).containsExactly("banana", 3L, 7L, 2L);
    }

    @Test
    void updateRejectsNonNullAssociation() {
        SnapshotAsset asset = new SnapshotAsset(3L, "anvil", new SnapshotOwner(1L, "alpha"));
        JdbcMeta mutable = new JdbcMeta();
        assertThat(mutable.entityType(SnapshotAsset.class).attributes()).isNotEmpty();

        assertThatThrownBy(() -> WriteSql.update(meta.entityType(SnapshotAsset.class), asset))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("关联属性写入未支持: " + SnapshotAsset.class.getName() + ".owner");
    }

    @Test
    void nextVersionAcceptsIntegerAndLongOnly() {
        assertThat(WriteSql.nextVersion(null)).isEqualTo(0);
        assertThat(WriteSql.nextVersion(2L)).isEqualTo(3L);
        assertThat(WriteSql.nextVersion(4)).isEqualTo(5);
        assertThatThrownBy(() -> WriteSql.nextVersion((short) 2))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageStartingWith("@Version 只支持 Integer / Long: ");
    }

    @Test
    void parametersAreImmutableCopy() {
        SnapshotItem item = new SnapshotItem(1L, "a", null, null, null, ItemStatus.NEW);
        SqlStatement statement = WriteSql.insert(meta.entityType(SnapshotItem.class), item);

        assertThat(statement.parameters()).containsExactly(1L, "a", null, null, null, "NEW");
        assertThatThrownBy(() -> statement.parameters().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
