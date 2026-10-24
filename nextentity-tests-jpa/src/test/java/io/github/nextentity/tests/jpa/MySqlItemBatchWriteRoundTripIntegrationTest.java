package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 批量写往返的真方言版：与 [ItemBatchWriteRoundTripTest] 同一套声明、同一套断言，只换连接。
///
/// 两边必须逐条对应——夹具或断言任一处不同，两边的结果集就没有可比性，那就不再是对拍。
/// 方言差异（H2 的 MySQL 兼容模式 vs 真 MySQL 8.0）只体现在语句骨架之外的地方，因此骨架断言两端一致。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemBatchWriteRoundTripIntegrationTest {

    private static final String WILDCARD_TEXT = "50%_off'quote\\back";

    private static final String UNICODE_TEXT = "名字🎉emoji";

    private static final String LONG_TEXT = "x".repeat(200);

    private static final String LONG_NOTE = "y".repeat(200);

    /// 实际下发的整句（归一化后）：列清单与占位符个数、别名、主键谓词都在里面，改一处就红。
    private static final String INSERT_ITEM =
            "insert into item (name,note,score,score2,status,id) values (?,?,?,?,?,?)";

    private static final String UPDATE_ITEM =
            "update item ie1_0 set name=?,note=?,score=?,score2=?,status=? where ie1_0.id=?";

    private static final List<Row> DECLARED = List.of(
            new Row(1, "plain", 0, 0, "note-1", ItemStatus.ACTIVE),
            new Row(2, "optional-nulls", null, null, null, null),
            new Row(3, WILDCARD_TEXT, 1, 2, "note-3", ItemStatus.NEW),
            new Row(4, UNICODE_TEXT, 5, null, UNICODE_TEXT, ItemStatus.DONE),
            new Row(5, LONG_TEXT, 7, 8, LONG_NOTE, ItemStatus.ACTIVE),
            new Row(6, "integer-bounds", Integer.MIN_VALUE, Integer.MAX_VALUE, null, ItemStatus.NEW),
            new Row(7, "score-null", null, 42, null, ItemStatus.DONE),
            new Row(8, "score2-null", -1, null, "note-8", ItemStatus.NEW));

    private final WriteRoundTripSupport<ItemEntity> support = WriteRoundTripSupport.onMySql(ItemEntity.class);

    /// 容器是同一个 JVM 共享的，一个用例留下的行会漏到下一个：每个用例前清表并丢掉受管状态。
    @BeforeEach
    void truncateAndClear() {
        support.truncate("ItemEntity");
        support.clear();
        support.clearSql();
    }

    /// 复位（AGENTS.md：集成测试启动容器后须在 `@AfterEach` 复位被改状态）：删掉本轮写入的行，
    /// 丢掉受管状态与语句账本，容器里的库回到空表。`@BeforeEach` 那条留作兜底——上一条用例
    /// 若崩在复位之前，它保证这一条仍从空表开始。
    @AfterEach
    void resetState() {
        support.truncate("ItemEntity");
        support.clear();
        support.clearSql();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    @Test
    void insertAllRoundTripsEveryDeclaredRow() {
        insertAll(DECLARED);

        List<Row> read = readByIds(ids(DECLARED));

        assertThat(read).containsExactlyElementsOf(DECLARED);
        assertThat(read)
                .filteredOn(row -> row.score() == null)
                .extracting(Row::id)
                .containsExactlyElementsOf(idsWhere(DECLARED, row -> row.score() == null));
        assertThat(read)
                .filteredOn(row -> row.score2() == null)
                .extracting(Row::id)
                .containsExactlyElementsOf(idsWhere(DECLARED, row -> row.score2() == null));
        assertThat(read)
                .filteredOn(row -> row.note() == null)
                .extracting(Row::id)
                .containsExactlyElementsOf(idsWhere(DECLARED, row -> row.note() == null));
        assertThat(read)
                .filteredOn(row -> row.status() == null)
                .extracting(Row::id)
                .containsExactlyElementsOf(idsWhere(DECLARED, row -> row.status() == null));
    }

    @Test
    void insertAllIssuesOneInsertPerDeclaredRow() {
        insertAll(DECLARED);

        List<String> inserts = statements(support.drainSql(), "insert into item");

        assertThat(inserts).containsExactlyElementsOf(Collections.nCopies(DECLARED.size(), INSERT_ITEM));
    }

    @Test
    void updateAllTouchesOnlyDeclaredRows() {
        insertAll(DECLARED);
        assertThat(readByIds(ids(DECLARED))).containsExactlyElementsOf(DECLARED);

        List<Row> declaredAfterUpdate = new ArrayList<>(DECLARED);
        declaredAfterUpdate.set(1, DECLARED.get(1).withName("optional-nulls-renamed"));
        declaredAfterUpdate.set(2, DECLARED.get(2).withName("wildcard-renamed"));
        declaredAfterUpdate.set(3, DECLARED.get(3).withName("unicode-renamed"));
        declaredAfterUpdate.set(4, DECLARED.get(4).withNote("note-renewed"));
        List<Row> changed = List.of(
                declaredAfterUpdate.get(1),
                declaredAfterUpdate.get(2),
                declaredAfterUpdate.get(3),
                declaredAfterUpdate.get(4));

        support.inTransaction(
                _ -> operations().updateAll(changed.stream().map(Row::toEntity).toList()));
        support.clear();

        assertThat(readByIds(ids(DECLARED))).containsExactlyElementsOf(declaredAfterUpdate);
    }

    @Test
    void updateAllIssuesOneUpdatePerRowWithPrimaryKeyPredicate() {
        insertAll(DECLARED);
        support.clearSql();

        List<Row> changed =
                List.of(DECLARED.get(0).withName("a-renamed"), DECLARED.get(5).withName("f-renamed"));
        support.inTransaction(
                _ -> operations().updateAll(changed.stream().map(Row::toEntity).toList()));
        support.clear();

        List<String> updates = statements(support.drainSql(), "update item");

        assertThat(updates).containsExactlyElementsOf(Collections.nCopies(changed.size(), UPDATE_ITEM));
    }

    @Test
    void deletingSubsetLeavesRemainingRowsUntouched() {
        insertAll(DECLARED);
        List<Long> doomed = List.of(DECLARED.get(5).id(), DECLARED.get(6).id());

        support.inTransaction(entityManager -> entityManager
                .createQuery("delete from ItemEntity i where i.id in :ids")
                .setParameter("ids", doomed)
                .executeUpdate());
        support.clear();

        List<Row> remaining =
                DECLARED.stream().filter(row -> !doomed.contains(row.id())).toList();

        assertThat(readAll()).containsExactlyElementsOf(remaining);
        assertThat(operations().query().count()).isEqualTo(remaining.size());
    }

    private EntityOperations<ItemEntity> operations() {
        return support.operations();
    }

    private void insertAll(List<Row> rows) {
        support.inTransaction(
                _ -> operations().insertAll(rows.stream().map(Row::toEntity).toList()));
        support.clear();
    }

    private List<Row> readByIds(List<Long> ids) {
        support.clear();
        PathRef<ItemEntity, Long> id = ItemEntity::id;
        BaseWhereStep<ItemEntity, ItemEntity> filtered =
                operations().query().where(id).in(ids);
        return filtered.orderBy(ItemEntity::id).asc().list().stream()
                .map(Row::of)
                .toList();
    }

    private List<Row> readAll() {
        support.clear();
        return operations().query().orderBy(ItemEntity::id).asc().list().stream()
                .map(Row::of)
                .toList();
    }

    private static List<Long> ids(List<Row> rows) {
        return rows.stream().map(Row::id).toList();
    }

    private static List<Long> idsWhere(List<Row> rows, Predicate<Row> condition) {
        return rows.stream().filter(condition).map(Row::id).toList();
    }

    /// 归一化成一句话（小写 + 空白压缩）后按前缀筛，断言侧就能整句比对。
    private static List<String> statements(List<String> sql, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return sql.stream()
                .map(WriteRoundTripSupport::normalized)
                .filter(statement -> statement.startsWith(lowered))
                .toList();
    }

    private record Row(long id, String name, Integer score, Integer score2, String note, ItemStatus status) {

        Row withName(String newName) {
            return new Row(id, newName, score, score2, note, status);
        }

        Row withNote(String newNote) {
            return new Row(id, name, score, score2, newNote, status);
        }

        ItemEntity toEntity() {
            return new ItemEntity(id, name, score, score2, note, status);
        }

        static Row of(ItemEntity entity) {
            return new Row(entity.id(), entity.name(), entity.score(), entity.score2(), entity.note(), entity.status());
        }
    }
}
