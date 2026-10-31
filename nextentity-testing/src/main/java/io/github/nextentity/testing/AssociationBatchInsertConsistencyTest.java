package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 批量插入里「关联为 null 的行」与「关联非 null 的行」混在同一批时，每行的外键必须各归各。
///
/// 背景：3.1 恢复写关联后，插入语句的列集随实体变化——关联非 `null` 才写外键列，
/// 为 `null` 时跳过该列。批量插入若用一个按首条语句准备好的 `PreparedStatement` 跑整批，
/// 混批就可能出现两种错法：参数个数对不上直接抛异常，或残留参数把后面的行静默写成
/// 首条的外键。两者都只有真库读回才看得见，所以本基类每条用例都走
/// 写入 → 清持久化上下文 → 原始列读回：
///
/// - 一批 3 行 `{非空, 空, 非空}`：行数 3，外键分别 `alpha`/`NULL`/`beta`；
/// - 一批 2 行 `{空, 非空}`：行数 2，外键分别 `NULL`/`alpha`。
///
/// 两个顺序都要跑：只测「空在前」抓不到静默污染，只测「非空在前」抓不到参数越界。
///
/// 固定夹具（owner 按首次出现顺序编号：`alpha`=1、`beta`=2）：
/// ```text
/// id=1 anvil   owner=alpha(1)
/// id=2 bandage owner=null
/// id=3 bolt    owner=beta(2)
/// ```
///
/// @param <T> 后端实体类型，必须实现 {@link Asset}
public abstract class AssociationBatchInsertConsistencyTest<T extends Asset> {

    protected static final long ANVIL_ID = 1L;

    protected static final long BANDAGE_ID = 2L;

    protected static final long BOLT_ID = 3L;

    protected static final String ALPHA = "alpha";

    protected static final String BETA = "beta";

    /// `alpha` 的 `@Id`。
    protected static final long ALPHA_OWNER_ID = 1L;

    /// `beta` 的 `@Id`。
    protected static final long BETA_OWNER_ID = 2L;

    /// 混批第一行的主键：关联非空。
    protected static final long WITH_OWNER_ID = 20L;

    /// 混批中间行的主键：关联为空。
    protected static final long WITHOUT_OWNER_ID = 21L;

    /// 混批最后一行的主键：关联非空（与外键列集不同的行排在首条之后）。
    protected static final long TRAILING_OWNER_ID = 22L;

    /// 空关联在前那一批的第一行主键。
    protected static final long LEADING_NULL_ID = 30L;

    /// 空关联在前那一批的第二行主键：关联非空。
    protected static final long LEADING_NULL_TRAILING_ID = 31L;

    /// 后端接缝：把「按夹具描述批量插入 + 原始列读回」包成后端中立的方法。
    ///
    /// @param <T> 后端实体类型
    public interface Fixture<T extends Asset> {

        /// 清空并写入夹具（owner 首次出现顺序即 id 1、2、…）。
        ///
        /// @param fixtures 夹具描述
        void seedData(List<AssetFixture> fixtures);

        /// 全新的查询入口：条件不得跨用例累积。
        ///
        /// @return 新的查询入口
        EntityQuery<T> query();

        /// 批量插入一批行：`ownerName` 为 `null` 的行不写外键；否则关联到夹具里同名 owner 的 `@Id`。
        ///
        /// @param rows 待插入行，按给定顺序进入同一批
        void insertAll(List<AssetFixture> rows);

        /// 原始列读回：`select owner_id from ... where id = ?`。
        ///
        /// @param assetId 主键
        /// @return 外键值；列为 `NULL` 时返回 `null`
        Long foreignKeyOf(long assetId);

        /// 丢弃一级缓存/持久化上下文，保证后面的读回真的来自数据库。
        void clearPersistenceContext();
    }

    @BeforeEach
    final void seed() {
        fixture()
                .seedData(List.of(
                        new AssetFixture(ANVIL_ID, "anvil", ALPHA),
                        new AssetFixture(BANDAGE_ID, "bandage", null),
                        new AssetFixture(BOLT_ID, "bolt", BETA)));
    }

    /// 后端接缝。
    ///
    /// @return 当前后端的夹具/读写适配
    protected abstract Fixture<T> fixture();

    @Test
    @DisplayName("混批 {非空, 空, 非空}：每行外键各归各，空关联行不被写成首条的外键")
    void mixedBatchKeepsEachRowsForeignKey() {
        Fixture<T> fixture = fixture();
        fixture.insertAll(List.of(
                new AssetFixture(WITH_OWNER_ID, "dynamo", ALPHA),
                new AssetFixture(WITHOUT_OWNER_ID, "ghost", null),
                new AssetFixture(TRAILING_OWNER_ID, "bolt-2", BETA)));
        fixture.clearPersistenceContext();

        assertThat(fixture.foreignKeyOf(WITH_OWNER_ID)).isEqualTo(ALPHA_OWNER_ID);
        assertThat(fixture.foreignKeyOf(WITHOUT_OWNER_ID))
                .as("关联为 null 的行外键必须是 NULL，不得残留同批首条的参数值")
                .isNull();
        assertThat(fixture.foreignKeyOf(TRAILING_OWNER_ID)).isEqualTo(BETA_OWNER_ID);
        assertThat(ids(fixture.query().list()))
                .as("整批 3 行都要落库，且原有 3 行不丢")
                .containsExactlyInAnyOrder(
                        ANVIL_ID, BANDAGE_ID, BOLT_ID, WITH_OWNER_ID, WITHOUT_OWNER_ID, TRAILING_OWNER_ID);
    }

    @Test
    @DisplayName("混批 {空, 非空}：空关联行在前时后一行的外键仍按自己的 @Id 写入，不抛参数越界")
    void nullAssociationFirstBatchKeepsEachRowsForeignKey() {
        Fixture<T> fixture = fixture();
        fixture.insertAll(List.of(
                new AssetFixture(LEADING_NULL_ID, "ghost-2", null),
                new AssetFixture(LEADING_NULL_TRAILING_ID, "dynamo-2", ALPHA)));
        fixture.clearPersistenceContext();

        assertThat(fixture.foreignKeyOf(LEADING_NULL_ID)).isNull();
        assertThat(fixture.foreignKeyOf(LEADING_NULL_TRAILING_ID)).isEqualTo(ALPHA_OWNER_ID);
        assertThat(ids(fixture.query().list()))
                .containsExactlyInAnyOrder(ANVIL_ID, BANDAGE_ID, BOLT_ID, LEADING_NULL_ID, LEADING_NULL_TRAILING_ID);
    }

    private List<Long> ids(List<T> rows) {
        return rows.stream().map(Asset::id).toList();
    }
}
