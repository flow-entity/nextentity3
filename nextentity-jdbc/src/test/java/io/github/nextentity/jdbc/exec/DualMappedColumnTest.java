package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

/// 同一列双映射的**镜像**形态：只读标量 + 同列名的可写外键。
///
/// 合法性与 legacy `Employee` 的「可写标量 + 只读外键」（`JdbcMetaTest#readOnlyForeignKeySharingScalarColumnIsAllowed`）
/// 完全同构，只是两侧角色对调：写侧只走可写的那一侧（外键），`SELECT` 只投影标量列
/// （`SqlProjections#entityColumns` 跳过全部关联列），整句 SQL 里该列只出现一次。
///
/// 缺陷形态（修前）：`JdbcEntityType#writableDuplicate` 只把「**先出现的是只读关联**」写成特例，
/// 标量侧没有对应分支，于是镜像形态落到「第二个是关联且可写 → 冲突」上，元数据期就抛
/// `同一实体内列名重复`。判据本应是「可写侧数量」，与**哪一侧**可写无关——`flattened` 的 javadoc
/// 与 CHANGELOG `[Unreleased]/Fixed`（FK 列名查重那条）都写「只有两侧都可写才是冲突」。
///
/// 放在 `exec` 包是因为证据要落在整句 SQL 上（`WriteSql` 不进 public 面，见 `WriteSqlTest`）。
class DualMappedColumnTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcDialect dialect = JdbcDialect.of("H2");
    private final JdbcCompiler compiler = new JdbcCompiler(meta, dialect);

    @Test
    void readOnlyScalarSharingColumnWithWritableForeignKeyIsAllowed() {
        JdbcEntityType<ReadOnlyScalarWritableForeignKey> type = meta.entityType(ReadOnlyScalarWritableForeignKey.class);

        assertThat(type.columns()).extracting(JdbcEntityAttribute::column).containsExactly("id", "owner_id", null);
        assertThat(type.columns()).extracting(JdbcEntityAttribute::joinColumn).containsExactly(null, null, "owner_id");
    }

    @Test
    void mirrorShapeIsAllowedRegardlessOfDeclarationOrder() {
        JdbcEntityType<WritableForeignKeyReadOnlyScalar> type = meta.entityType(WritableForeignKeyReadOnlyScalar.class);

        assertThat(type.columns()).extracting(JdbcEntityAttribute::column).containsExactly("id", null, "owner_id");
        assertThat(type.columns()).extracting(JdbcEntityAttribute::joinColumn).containsExactly(null, "owner_id", null);
    }

    @Test
    void writableForeignKeyIsTheOnlyWriterAndScalarIsTheOnlyProjection() {
        JdbcEntityType<ReadOnlyScalarWritableForeignKey> type = meta.entityType(ReadOnlyScalarWritableForeignKey.class);
        ReadOnlyScalarWritableForeignKey entity =
                new ReadOnlyScalarWritableForeignKey(7L, 99L, new SnapshotOwner(42L, "acme"));

        SqlStatement insert = WriteSql.insert(meta, type, entity, dialect);
        assertThat(insert.sql()).isEqualTo("insert into review_read_only_scalar (id, owner_id) values (?, ?)");
        assertThat(insert.parameters()).as("写的是关联对象的 @Id，不是只读标量的值").containsExactly(7L, 42L);
        assertThat(occurrences(insert.sql(), "owner_id")).isEqualTo(1);

        SqlStatement update = WriteSql.update(meta, type, entity, dialect);
        assertThat(update.sql()).isEqualTo("update review_read_only_scalar set owner_id=? where id=?");
        assertThat(update.parameters()).containsExactly(42L, 7L);
        assertThat(occurrences(update.sql(), "owner_id")).isEqualTo(1);

        String select = compiler.compile(QuerySpec.of(ReadOnlyScalarWritableForeignKey.class))
                .sql();
        assertThat(select).isEqualTo("select t0.id, t0.owner_id from review_read_only_scalar t0");
        assertThat(occurrences(select, "owner_id")).isEqualTo(1);
    }

    @Test
    void readOnlyForeignKeyWithWritableScalarStillWritesTheScalarOnly() {
        JdbcEntityType<WritableScalarReadOnlyForeignKey> type = meta.entityType(WritableScalarReadOnlyForeignKey.class);
        WritableScalarReadOnlyForeignKey entity =
                new WritableScalarReadOnlyForeignKey(7L, 99L, new SnapshotOwner(42L, "acme"));

        SqlStatement update = WriteSql.update(meta, type, entity, dialect);
        assertThat(update.sql()).isEqualTo("update review_writable_scalar set owner_id=? where id=?");
        assertThat(update.parameters()).as("legacy 方向：写的是标量的值").containsExactly(99L, 7L);
        assertThat(occurrences(update.sql(), "owner_id")).isEqualTo(1);
    }

    @Test
    void bothSidesWritableIsStillRejected() {
        assertThatThrownBy(() -> meta.entityType(BothWritableCollision.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("同一实体内列名重复: " + BothWritableCollision.class.getName() + " -> owner_id"
                        + "（同一可嵌入类型多次嵌入请用 @AttributeOverride 指定不同列名）");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    /// 镜像形态：只读标量在前、可写外键在后。
    @Entity
    @Table(name = "review_read_only_scalar")
    static class ReadOnlyScalarWritableForeignKey {

        @Id
        private Long id;

        @Column(name = "owner_id", insertable = false, updatable = false)
        private Long ownerId;

        @ManyToOne
        @JoinColumn(name = "owner_id")
        private SnapshotOwner owner;

        protected ReadOnlyScalarWritableForeignKey() {}

        ReadOnlyScalarWritableForeignKey(Long id, Long ownerId, SnapshotOwner owner) {
            this.id = id;
            this.ownerId = ownerId;
            this.owner = owner;
        }
    }

    /// 同一镜像形态，但声明序反过来（可写外键在前、只读标量在后）：判据与顺序无关。
    @Entity
    @Table(name = "review_writable_foreign_key")
    static class WritableForeignKeyReadOnlyScalar {

        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "owner_id")
        private SnapshotOwner owner;

        @Column(name = "owner_id", insertable = false, updatable = false)
        private Long ownerId;

        protected WritableForeignKeyReadOnlyScalar() {}

        WritableForeignKeyReadOnlyScalar(Long id, Long ownerId, SnapshotOwner owner) {
            this.id = id;
            this.ownerId = ownerId;
            this.owner = owner;
        }
    }

    /// legacy 方向：可写标量在前、只读外键在后（与 `JdbcMetaTest` 的夹具同形，这里用来钉整句 SQL）。
    @Entity
    @Table(name = "review_writable_scalar")
    static class WritableScalarReadOnlyForeignKey {

        @Id
        private Long id;

        @Column(name = "owner_id")
        private Long ownerId;

        @ManyToOne
        @JoinColumn(name = "owner_id", insertable = false, updatable = false)
        private SnapshotOwner owner;

        protected WritableScalarReadOnlyForeignKey() {}

        WritableScalarReadOnlyForeignKey(Long id, Long ownerId, SnapshotOwner owner) {
            this.id = id;
            this.ownerId = ownerId;
            this.owner = owner;
        }
    }

    /// 两侧都可写：真冲突，必须仍拒绝。
    @Entity
    @Table(name = "review_both_writable")
    static class BothWritableCollision {

        @Id
        private Long id;

        @Column(name = "owner_id")
        private Long ownerId;

        @ManyToOne
        @JoinColumn(name = "owner_id")
        private SnapshotOwner owner;

        protected BothWritableCollision() {}
    }
}
