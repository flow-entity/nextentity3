package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.model.Slice;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// 保留字标识符的一致性用例：表名 `order`、列名 `desc` / `key` 在四个方言上都必须可用。
///
/// 夹具四行（每个用例前重建，全部非空——这一套不考察 NULL 排序，方言 NULL 位置差异由
/// 排序差分用例负责）：
/// ```text
/// id=1 name=apple      desc=first   key=10
/// id=2 name=banana     desc=second  key=20
/// id=3 name=cherry     desc=third   key=30
/// id=4 name=apple pie  desc=fourth  key=40
/// ```
///
/// 本类只依赖 {@link ReservedWord} 接口与 [ReservedWordSupport]，不出现任何后端专有类型；
/// 实体注解用反引号包标识符，具体渲染由各后端方言完成（JDBC 的 H2/PG 用双引号、MySQL 用
/// 反引号、SQL Server 用方括号；Hibernate 在 H2 上也用双引号——两边渲染形态一致，才能对拍）。
///
/// @param <T> 后端实体类型，必须实现 {@link ReservedWord}
public abstract class ReservedWordConsistencyTest<T extends ReservedWord> {

    protected static final long APPLE_ID = 1L;

    protected static final long BANANA_ID = 2L;

    protected static final long CHERRY_ID = 3L;

    protected static final long APPLE_PIE_ID = 4L;

    private static final String APPLE_NAME = "apple";

    private static final String FIRST_DESC = "first";

    private static final String KIWI_NAME = "kiwi";

    private static final String FIFTH_DESC = "fifth";

    private static final String UPDATED_NAME = "apple x";

    private static final String UPDATED_DESC = "changed";

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture(APPLE_ID, APPLE_NAME, FIRST_DESC, 10),
            new Fixture(BANANA_ID, "banana", "second", 20),
            new Fixture(CHERRY_ID, "cherry", "third", 30),
            new Fixture(APPLE_PIE_ID, "apple pie", "fourth", 40));

    /// 每个用例前重建夹具。
    @BeforeEach
    final void seed() {
        support()
                .seedData(FIXTURES.stream()
                        .map(f -> support().newReservedWord(f.id(), f.name(), f.desc(), f.key()))
                        .toList());
    }

    /// 后端接线（由具体后端提供）。
    ///
    /// @return 保留字能力集
    protected abstract ReservedWordSupport<T> support();

    /// 单行插入：插入走生产写入路径，读回时关键字列可正常映射。
    @Test
    void insertsSingleRow() {
        support().insert(support().newReservedWord(9L, KIWI_NAME, FIFTH_DESC, 50));

        T row = support().query().where(support().idRef()).eq(9L).first();

        assertThat(row.name()).isEqualTo(KIWI_NAME);
        assertThat(row.desc()).isEqualTo(FIFTH_DESC);
        assertThat(row.key()).isEqualTo(50);
    }

    /// 批量插入：`insertAll` 后全部行可查询。
    @Test
    void insertAllWritesEveryRow() {
        support()
                .insertAll(List.of(
                        support().newReservedWord(7L, "mango", "sixth", 60),
                        support().newReservedWord(8L, "olive", "seventh", 70)));

        List<T> rows = support()
                .query()
                .where(support().keyRef())
                .gt(40)
                .orderBy(support().keyRef())
                .asc()
                .list();

        assertThat(rows).extracting(ReservedWord::id).containsExactly(7L, 8L);
    }

    /// 更新：按主键改写 `name` / `desc` / `key` 三个可写列（其中两个是关键字列）。
    @Test
    void updateRewritesWritableColumns() {
        support().update(support().newReservedWord(APPLE_ID, UPDATED_NAME, UPDATED_DESC, 11));

        T row = support().query().where(support().idRef()).eq(APPLE_ID).first();

        assertThat(row.name()).isEqualTo(UPDATED_NAME);
        assertThat(row.desc()).isEqualTo(UPDATED_DESC);
        assertThat(row.key()).isEqualTo(11);
    }

    /// 关键字列做等值 / 不等值条件。
    @Test
    void filtersByKeywordDesc() {
        List<T> eq = support().query().where(support().descRef()).eq(FIRST_DESC).list();

        assertThat(eq).extracting(ReservedWord::id).containsExactly(APPLE_ID);

        List<T> ne = support().query().where(support().descRef()).ne(FIRST_DESC).list();

        assertThat(ne).extracting(ReservedWord::id).containsExactlyInAnyOrder(BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
    }

    /// 关键字列做范围条件。
    @Test
    void filtersByKeywordKey() {
        List<T> gt = support()
                .query()
                .where(support().keyRef())
                .gt(20)
                .orderBy(support().keyRef())
                .asc()
                .list();

        assertThat(gt).extracting(ReservedWord::id).containsExactly(CHERRY_ID, APPLE_PIE_ID);

        List<T> between = support()
                .query()
                .where(support().keyRef())
                .between(15, 35)
                .orderBy(support().keyRef())
                .asc()
                .list();

        assertThat(between).extracting(ReservedWord::id).containsExactly(BANANA_ID, CHERRY_ID);
    }

    /// `count` / `exists` 在关键字列条件上生效。
    @Test
    void countsAndExistsOnKeywordColumns() {
        assertThat(support().query().count()).isEqualTo(4L);

        assertThat(support().query().where(support().keyRef()).ge(20).count()).isEqualTo(3L);

        assertThat(support().query().where(support().descRef()).like("f%").exists())
                .isTrue();

        assertThat(support().query().where(support().descRef()).eq("zzz").exists())
                .isFalse();
    }

    /// 关键字列参与排序（`orderBy(desc)`）。
    @Test
    void ordersByKeywordDesc() {
        List<T> rows = support().query().orderBy(support().descRef()).asc().list();

        assertThat(rows).extracting(ReservedWord::id).containsExactly(APPLE_ID, APPLE_PIE_ID, BANANA_ID, CHERRY_ID);
    }

    /// 关键字列参与排序与分页（`orderBy(key)` + `slice`）。
    @Test
    void slicesWithKeywordOrderBy() {
        Slice<T> page = support().query().orderBy(support().keyRef()).asc().slice(1, 2);

        assertThat(page.data()).extracting(ReservedWord::id).containsExactly(BANANA_ID, CHERRY_ID);
        assertThat(page.total()).isEqualTo(4L);
    }

    /// 关键字列上的模糊匹配：`like` 与 `contains` 都命中正确行。
    @Test
    void likeOnKeywordColumn() {
        List<T> like = support().query().where(support().descRef()).like("sec%").list();

        assertThat(like).extracting(ReservedWord::id).containsExactly(BANANA_ID);

        List<T> contains =
                support().query().where(support().descRef()).contains("ird").list();

        assertThat(contains).extracting(ReservedWord::id).containsExactly(CHERRY_ID);

        List<T> missing =
                support().query().where(support().descRef()).contains("zzz").list();

        assertThat(missing).isEmpty();
    }

    /// 名称列与关键字列组合条件（不是只看关键字列）。
    @Test
    void combinesPlainAndKeywordColumns() {
        List<T> rows = support()
                .query()
                .where(support().nameRef())
                .contains(APPLE_NAME)
                .where(support().keyRef())
                .gt(10)
                .list();

        assertThat(rows).extracting(ReservedWord::id).containsExactly(APPLE_PIE_ID);
    }

    /// 夹具原始数据。
    ///
    /// @param id   主键
    /// @param name 名称
    /// @param desc 关键字列 `desc`
    /// @param key  关键字列 `key`
    private record Fixture(long id, String name, String desc, Integer key) {}
}
