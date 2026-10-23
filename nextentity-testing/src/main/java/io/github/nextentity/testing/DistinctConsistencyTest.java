package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
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
    @Test
    void distinctCollapsesDuplicateProjectedRows() {
        List<Tuple2<ItemStatus, String>> rows = query().distinct()
                .select(Path.of(statusRef()), Path.of(noteRef()))
                .list();

        assertThat(rows).hasSize(3);
        assertThat(rows.stream()
                        .filter(row -> row.get0() == ItemStatus.NEW && row.get1() == null)
                        .count())
                .isEqualTo(1L);
    }

    /// 三列去重：列数不受 2 限制；夹具四行本就互不相同，因此一行都不折。
    @Test
    void distinctAppliesToThreeColumns() {
        List<Tuple3<ItemStatus, String, Integer>> rows = query().distinct()
                .select(Path.of(statusRef()), Path.of(noteRef()), Path.of(score2Ref()))
                .list();

        assertThat(rows).hasSize(4);
        assertThat(rows.getFirst().size()).isEqualTo(3);
        // 没有 orderBy 时行序由数据库决定，只能按集合断言列 0 的取值
        assertThat(rows)
                .extracting(Tuple3::get0)
                .containsExactlyInAnyOrder(ItemStatus.NEW, ItemStatus.ACTIVE, ItemStatus.DONE, ItemStatus.NEW);
    }
}
