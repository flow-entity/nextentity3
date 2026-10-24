package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/// `distinct()` 修饰符的一致性：它只作用于**紧随其后的那一次投影**，且 1 列 / 2 列 / 3 列一视同仁。
///
/// 夹具里 `note` 为 `null` 的两行（id=1、id=4）是去重的关键：SQL 的 `DISTINCT` 把 `NULL` 当作一个分组，
/// 因此单列去重得到 3 个值而不是 4 个。这与其余用例对 `null` 的处理口径一致。
///
/// @param <T> 后端实体类型
public abstract class DistinctConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 单列去重：`null` 只算一个分组，非空值各占一个。
    @Test
    void distinctSingleColumnCollapsesNullIntoOneGroup() {
        List<String> notes = query().distinct().select(noteRef()).list();

        assertThat(notes).hasSize(3);
        assertThat(notes.stream().filter(Objects::nonNull).toList()).containsExactlyInAnyOrder(NOTE_1, NOTE_2);
    }

    /// 多列去重：完全相同的投影行被折成一行（夹具里两行 `(NEW, null)` 只剩一行）。
    ///
    /// 断言直接钉住三行的**完整投影值**，不用 `filter(...).count()`：夹具里没有「`status` 为 `NEW` 且
    /// `note` 非空」的行，`get0() == ItemStatus.NEW && get1() == null` 的第二个操作数永远为真、短路一侧
    /// 走不到——那种写法会把「只验了一行」伪装成「验了去重」。集合断言同时钉住元素个数：`DISTINCT`
    /// 失效会多出重复行，断言直接失败。
    ///
    /// 顺序无关是查询本身的语义：没有 `orderBy` 时行序由数据库决定（`Collector` 只在 `list(int)` /
    /// `first()` 上自动按主键排序），因此用 `containsExactlyInAnyOrder` 而不是 `containsExactly`。
    @Test
    void distinctCollapsesDuplicateProjectedRows() {
        List<Tuple2<ItemStatus, String>> rows = query().distinct()
                .select(Path.of(statusRef()), Path.of(noteRef()))
                .list();

        assertThat(rows)
                .containsExactlyInAnyOrder(
                        new Tuple2<>(ItemStatus.NEW, null),
                        new Tuple2<>(ItemStatus.ACTIVE, NOTE_1),
                        new Tuple2<>(ItemStatus.DONE, NOTE_2));
    }

    /// 三列去重：列数不受 2 限制；夹具四行本就互不相同，因此一行都不折。
    @Test
    void distinctAppliesToThreeColumns() {
        List<Tuple3<ItemStatus, String, Integer>> rows = query().distinct()
                .select(Path.of(statusRef()), Path.of(noteRef()), Path.of(score2Ref()))
                .list();

        assertThat(rows).hasSize(4);
        assertThat(rows.getFirst().size()).isEqualTo(3);
        // 三列**逐列**钉住：只断列 0 的话，列 1 / 列 2 取错（错列、少列、错位）照样绿。
        // 顺序无关是查询本身的语义（`distinct()` 投影不自动按主键排序），见 `Collector` 类注释。
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        new Tuple3<>(ItemStatus.NEW, null, 10),
                        new Tuple3<>(ItemStatus.ACTIVE, NOTE_1, 10),
                        new Tuple3<>(ItemStatus.DONE, NOTE_2, null),
                        new Tuple3<>(ItemStatus.NEW, null, 5));
    }

    /// 去重投影 + 分页：`list(n)` / `first()` / `slice(o, l)` 必须可执行（原因见 `QueryBuilder#spec`），
    /// 且三者取到的值都必须落在**同一次 `list()` 的去重结果**里。
    ///
    /// 为什么不是 `containsExactly`：`distinct()` 投影不自动按主键排序（见 `Collector` 类注释），
    /// 行序由数据库决定，所以能钉的精确口径是「取值集合 + 无重复 + 个数」，行序不在断言范围内。
    /// 只断 `hasSize(2)` / `isNotNull()` 时，返回重复行、返回错列、返回去重前的行都照样通过。
    @Test
    void distinctSurvivesPaginationWithoutExplicitOrder() {
        List<String> distinctNotes = query().distinct().select(noteRef()).list();
        assertThat(distinctNotes).hasSize(3).containsExactlyInAnyOrder(NOTE_1, NOTE_2, null);

        List<String> firstTwo = query().distinct().select(noteRef()).list(2);
        assertThat(firstTwo).hasSize(2).doesNotHaveDuplicates().isSubsetOf(NOTE_1, NOTE_2, null);

        // `note` 可空：去重后有一组是 NULL，`first()` 可能正好取到它，因此换非空列 `status` 验取值
        List<ItemStatus> distinctStatuses =
                query().distinct().select(statusRef()).list();
        assertThat(distinctStatuses).containsExactlyInAnyOrder(ItemStatus.NEW, ItemStatus.ACTIVE, ItemStatus.DONE);
        assertThat(query().distinct().select(statusRef()).first()).isIn(distinctStatuses.toArray());

        Slice<String> slice = query().distinct().select(noteRef()).slice(0, 2);
        assertThat(slice.data()).hasSize(2).doesNotHaveDuplicates().isSubsetOf(NOTE_1, NOTE_2, null);
        // total 与 `count()` 同口径，而 `COUNT(DISTINCT)` 不计 NULL：三组（NULL / n1 / n2）里只算 2。
        // 这是已知口径（见 `Slice#total`），钉住以免被当成回归修掉。
        assertThat(slice.total()).isEqualTo(2);
    }
}
