package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.StringPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 写关联（CUT-17）的一致性：外键必须从**写进去的关联对象的 `@Id`** 得出，并且在新会话里读回同一个值。
///
/// 这是 3.0.0 裁掉的那块能力面：当时"写关联没有正例"，于是"只读才有用"成了裁剪理由。
/// 本基类用差分/往返口径把正例钉住，而不是断言某条 SQL 的形状：
///
/// - 写 `asset.owner` → 读回 `owner_id` 等于这个 owner 的 `@Id`，且关联路径查询能重新命中它；
/// - 改关联 → 旧 owner 不再命中、新 owner 命中，且其它行不动；
/// - 关联为 `null` 时的后端差异按文档已披露的口径分别断言（见 USAGE §13.4.4）：
///   JPA 写 `NULL`，JDBC **跳过该列**（所以 `update(entity)` 无法置空外键，需用条件更新 + `Path` 重载）。
///   两个分支都在本类里跑，不做整条用例的跳过——跳过会变成"没测也不知道"，而这里两种语义都要可数地钉住。
///
/// 固定夹具（owner 按首次出现顺序编号：`alpha`=1、`beta`=2）：
/// ```text
/// id=1 anvil   owner=alpha(1)
/// id=2 bandage owner=null      ← 空关联锚点
/// id=3 bolt    owner=beta(2)
/// ```
///
/// @param <T> 后端实体类型
public abstract class AssociationWriteConsistencyTest<T extends Asset> {

    protected static final long ANVIL_ID = 1L;

    protected static final long BANDAGE_ID = 2L;

    protected static final long BOLT_ID = 3L;

    /// 新插入的一行：写关联后读回的锚点。
    protected static final long NEW_ASSET_ID = 10L;

    /// 新插入的一行：写入时没有关联。
    protected static final long GHOST_ASSET_ID = 11L;

    protected static final String ALPHA = "alpha";

    protected static final String BETA = "beta";

    protected static final String ANVIL_NAME = "anvil";

    protected static final String DYNAMO_NAME = "dynamo";

    protected static final String BOLT_RENAMED_NAME = "bolt-renamed";

    /// 关联为 `null` 时后端的两种语义：都是已披露的行为，不是缺口。
    public enum NullAssociationBehavior {
        /// 写 `NULL`：外键被清掉（JPA，见 USAGE §13.4.4）。
        CLEARS_FOREIGN_KEY,
        /// 跳过该列：外键保持旧值，`update(entity)` 无法置空（JDBC，见 USAGE §13.4.4）。
        SKIPS_COLUMN
    }

    /// 后端接缝：把 8 个差异点收进一个接口，避免基类因方法数超限而拆家。
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

        /// 两段嵌套路径：`EntityPath.of(T::owner).get(Owner::name)`。
        ///
        /// @return 嵌套字符串路径
        StringPath<T> ownerNamePath();

        /// 单实体写入一行：`ownerId` 为 `null` 时关联为空。
        ///
        /// @param assetId 主键
        /// @param name    名称
        /// @param ownerId 关联对象的 `@Id`
        void insert(long assetId, String name, Long ownerId);

        /// 单实体更新一行：`ownerId` 为 `null` 时按后端语义处理（见 {@link NullAssociationBehavior}）。
        ///
        /// @param assetId 主键
        /// @param name    名称
        /// @param ownerId 关联对象的 `@Id`
        void update(long assetId, String name, Long ownerId);

        /// 原始列读回：`select owner_id from asset where id = ?`。
        ///
        /// @param assetId 主键
        /// @return 外键值；列为 `NULL` 时返回 `null`
        Long foreignKeyOf(long assetId);

        /// 原始列读回：`select name from asset where id = ?`。
        ///
        /// @param assetId 主键
        /// @return 名称
        String nameOf(long assetId);

        /// 丢弃一级缓存/持久化上下文，保证后面的读回真的来自数据库。
        void clearPersistenceContext();

        /// 该后端的已披露语义。
        ///
        /// @return 空关联行为
        NullAssociationBehavior nullAssociationBehavior();
    }

    @BeforeEach
    final void seed() {
        fixture()
                .seedData(List.of(
                        new AssetFixture(ANVIL_ID, ANVIL_NAME, ALPHA),
                        new AssetFixture(BANDAGE_ID, "bandage", null),
                        new AssetFixture(BOLT_ID, "bolt", BETA)));
    }

    /// 后端接缝。
    ///
    /// @return 当前后端的夹具/读写适配
    protected abstract Fixture<T> fixture();

    /// 取结果集的主键序列。
    ///
    /// @param rows 查询结果
    /// @return 主键列表
    protected final List<Long> ids(List<T> rows) {
        return rows.stream().map(Asset::id).toList();
    }

    @Test
    @DisplayName("写关联：外键取关联对象的 @Id，新会话里读回同一个值且关联路径重新命中")
    void insertWritesReferencedIdAndReadsBackInNewSession() {
        Fixture<T> fixture = fixture();
        fixture.insert(NEW_ASSET_ID, DYNAMO_NAME, 1L);
        fixture.clearPersistenceContext();

        assertThat(fixture.foreignKeyOf(NEW_ASSET_ID))
                .as("写关联必须把关联对象的 @Id 落成外键（区分「读了对象」与「写了 null」）")
                .isEqualTo(1L);
        assertThat(ids(fixture.query().where(fixture.ownerNamePath()).eq(ALPHA).list()))
                .as("新会话按关联路径查询必须命中刚写进去的那一行")
                .contains(NEW_ASSET_ID);
    }

    @Test
    @DisplayName("写空关联：外键为 NULL，关联路径 isNull 命中该行")
    void insertWithoutOwnerLeavesForeignKeyNull() {
        Fixture<T> fixture = fixture();
        fixture.insert(GHOST_ASSET_ID, "ghost", null);
        fixture.clearPersistenceContext();

        assertThat(fixture.foreignKeyOf(GHOST_ASSET_ID)).isNull();
        assertThat(ids(fixture.query().where(fixture.ownerNamePath()).isNull().list()))
                .as("空关联必须能被关联路径的 isNull 命中（否则是 join 把 NULL 行丢了）")
                .contains(GHOST_ASSET_ID);
    }

    @Test
    @DisplayName("改关联：新外键落库，旧 owner 不再命中该行、新 owner 命中且其它行不动")
    void updateSwitchesToNewForeignKey() {
        Fixture<T> fixture = fixture();
        fixture.update(ANVIL_ID, ANVIL_NAME, 2L);
        fixture.clearPersistenceContext();

        assertThat(fixture.foreignKeyOf(ANVIL_ID)).isEqualTo(2L);
        assertThat(ids(fixture.query().where(fixture.ownerNamePath()).eq(BETA).list()))
                .as("新 owner 必须命中被改的行")
                .contains(ANVIL_ID, BOLT_ID);
        assertThat(ids(fixture.query().where(fixture.ownerNamePath()).eq(ALPHA).list()))
                .as("旧 owner 的查询不得再命中被改的行")
                .doesNotContain(ANVIL_ID);
    }

    @Test
    @DisplayName("置空关联：按 USAGE §13.4.4 披露的后端语义断言（JPA 写 NULL / JDBC 跳过列）")
    void updateClearingAssociationFollowsDisclosedBackendSemantics() {
        Fixture<T> fixture = fixture();
        fixture.update(BOLT_ID, BOLT_RENAMED_NAME, null);
        fixture.clearPersistenceContext();

        assertThat(fixture.nameOf(BOLT_ID)).as("无论哪种语义，标量列都要真的被更新（否则下面的大分支是空转）").isEqualTo(BOLT_RENAMED_NAME);
        if (fixture.nullAssociationBehavior() == NullAssociationBehavior.CLEARS_FOREIGN_KEY) {
            assertThat(fixture.foreignKeyOf(BOLT_ID))
                    .as("JPA：关联置 null 必须写 NULL")
                    .isNull();
            assertThat(ids(fixture.query()
                            .where(fixture.ownerNamePath())
                            .isNull()
                            .list()))
                    .as("JPA：置空后该行必须命中嵌套路径 isNull")
                    .contains(BOLT_ID);
            assertThat(ids(fixture.query()
                            .where(fixture.ownerNamePath())
                            .eq(BETA)
                            .list()))
                    .as("JPA：置空后旧 owner 不得再命中")
                    .doesNotContain(BOLT_ID);
        } else {
            assertThat(fixture.foreignKeyOf(BOLT_ID))
                    .as("JDBC：关联为 null 时跳过该列，外键保持旧值（USAGE §13.4.4）")
                    .isEqualTo(2L);
            assertThat(ids(fixture.query()
                            .where(fixture.ownerNamePath())
                            .eq(BETA)
                            .list()))
                    .as("JDBC：外键未变，旧 owner 仍命中")
                    .contains(BOLT_ID);
        }
    }

    @Test
    @DisplayName("写关联是往返的：读回的关联路径与写入的 owner 名称一致，不串到相邻行")
    void insertedAssociationsDoNotLeakBetweenRows() {
        Fixture<T> fixture = fixture();
        fixture.insert(NEW_ASSET_ID, DYNAMO_NAME, 2L);
        fixture.clearPersistenceContext();

        assertThat(ids(fixture.query().where(fixture.ownerNamePath()).eq(BETA).list()))
                .as("新写 beta 关联只应新增一行，不得影响原有 beta 行")
                .containsExactlyInAnyOrder(BOLT_ID, NEW_ASSET_ID);
        assertThat(ids(fixture.query().where(fixture.ownerNamePath()).eq(ALPHA).list()))
                .as("写 beta 关联不得把 alpha 行挪走")
                .containsExactly(ANVIL_ID);
    }
}
