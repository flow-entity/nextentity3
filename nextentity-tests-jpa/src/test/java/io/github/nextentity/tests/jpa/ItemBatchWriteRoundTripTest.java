package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 批量写的**往返差分**：用例先声明「我要写进去哪些行」，再只通过全新查询读回来逐行比对。
///
/// 期望值只由声明产生——声明里是字面量，读回侧是实体访问器，两条路径不相交：写路径漏列、错列、
/// 少 flush、批量漏行、把某列写成默认值，都会让读回的对不上声明。既有
/// `ItemBatchWriteConsistencyTest` 钉的是「version 自增且回写调用方对象」，
/// 这里补的是**全部列 × 边界值**的往返，以及下发的语句骨架（比值断言看不见「多写一条再覆盖回来」）。
class ItemBatchWriteRoundTripTest {

    /// `%` / `_` / `'` / `\` 会被拼进 LIKE 与 SQL 字面量；emoji 走的是 4 字节字符，验证编码往返。
    private static final String WILDCARD_TEXT = "50%_off'quote\\back";

    private static final String UNICODE_TEXT = "名字🎉emoji";

    private static final String LONG_TEXT = "x".repeat(200);

    private static final String LONG_NOTE = "y".repeat(200);

    /// 写入意图：四行全列非空、一行可选列全 null、边界文本、整数上下界、score 与 score2 各缺一个。
    private static final List<Row> DECLARED = List.of(
            new Row(1, "plain", 0, 0, "note-1", ItemStatus.ACTIVE),
            new Row(2, "optional-nulls", null, null, null, null),
            new Row(3, WILDCARD_TEXT, 1, 2, "note-3", ItemStatus.NEW),
            new Row(4, UNICODE_TEXT, 5, null, UNICODE_TEXT, ItemStatus.DONE),
            new Row(5, LONG_TEXT, 7, 8, LONG_NOTE, ItemStatus.ACTIVE),
            new Row(6, "integer-bounds", Integer.MIN_VALUE, Integer.MAX_VALUE, null, ItemStatus.NEW),
            new Row(7, "score-null", null, 42, null, ItemStatus.DONE),
            new Row(8, "score2-null", -1, null, "note-8", ItemStatus.NEW));

    private final WriteRoundTripSupport<ItemEntity> support =
            WriteRoundTripSupport.onH2("item_batch_write_round_trip", ItemEntity.class);

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 一次 `insertAll` 写八行，读回必须逐行逐列等于声明。
    ///
    /// `NULL` 尤其要盯：被写成 0 / 空串 / 枚举首项之后，任何「按 null 过滤」的查询都会悄悄少行。
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

    /// 语句骨架：一行一条 `insert into item`，且每条都带全列。
    ///
    /// 钉的是 `PersistExecutor` 文档里的「逐条写入」——悄悄退化成一条多值 INSERT 或反过来
    /// 把批量拆成不带全列的写法，都会在这里现形。真要做 JDBC batching，必须同时改这条用例，
    /// 那就变成一次「有人决定过」的改动，而不是无声的漂移。
    @Test
    void insertAllIssuesOneInsertPerDeclaredRow() {
        insertAll(DECLARED);

        List<String> inserts = statements(support.drainSql(), "insert into item");

        assertThat(inserts).hasSize(DECLARED.size());
        assertThat(inserts).allSatisfy(sql -> assertThat(sql)
                .contains("name")
                .contains("score")
                .contains("score2")
                .contains("note")
                .contains("status")
                .contains("values ("));
    }

    /// 批量 `updateAll` 只给四行：改了的必须对，没改的一个字都不能动。
    ///
    /// 构造的是「部分行更新」：如果执行器把整批之外的行走了一遍（例如拿了缓存里的旧列表），
    /// 或者 SET 里漏了某列让该列被写成 null，读回就与 `declaredAfterUpdate` 对不上。
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

    /// 语句骨架：一行一条 `update item … set …`，且每条都带主键条件。
    ///
    /// 少了 `where id` 的批量 update 会把整表写成同一行的值——比值断言在「只测一行」时完全看不出来。
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

        assertThat(updates).hasSize(changed.size());
        assertThat(updates).allSatisfy(sql -> assertThat(sql)
                .contains("where ")
                .contains("id=?")
                .contains("name=")
                .contains("score="));
    }

    /// 删掉一部分之后，剩下的行必须逐列不变、数量对得上。
    ///
    /// 库的写接缝没有 delete（`PersistExecutor` 只有 `insert` / `insertAll` / `update` / `updateAll`，
    /// 删除在 point-mall 侧来自 Spring Data），所以这一格用 JPQL 批量删除。它证明的不是删除语义，
    /// 而是两件事：删除条件只命中声明的那几行（剩余集合 = 声明减去被删），以及读回路径确实走库
    /// （`clear()` 之后取到的是库里的行，不是持久化上下文里的残留）。
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

    private static List<String> statements(List<String> sql, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return sql.stream()
                .filter(statement -> statement.toLowerCase(Locale.ROOT).startsWith(lowered))
                .toList();
    }

    /// 一行写入意图。`toEntity()` 只是把声明搬成实体，不做任何换算——期望值不经过生产代码。
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
