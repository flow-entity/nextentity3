package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.api.UpdateSetStep;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 条件写的「SET 目标是关联列」的一致性（两后端同一套用例）。
///
/// 钉住三件事：
///
/// - **外键列可以写**：`set(EntityPath.of(T::owner).get(Owner::getId), null)` 把外键置空、
///   `set(同一路径, <id>)` 把外键改成另一行——写完后从原始列读回，并确认关联路径查询跟着变；
/// - **关联表的普通列不能写**：`set(EntityPath.of(T::owner).get(Owner::getName), "x")` 必须
///   fail-fast 抛 [UnsupportedOperationException]（`owner.name` 不是本表列，不能渲染成
///   `set name = ?`）。这里只断言异常类型：两后端消息文案不同属预期，逐字文案由各自模块的
///   快照/golden 用例钉；
/// - **失败的写不能留下半截**：被拒之后外键与本表标量列都保持原值（走真库读回，不看返回值）。
///
/// 本表是否存在 `@JoinColumn(updatable=false)` 的关联由夹具决定：当前两个后端的关联夹具
/// （`AssetEntity.owner`）都是可写的，所以「只读关联列的嵌套 SET 被拒」不在本基类覆盖，
/// 它由各后端自己的条件写只读列用例负责；本基类不为了凑覆盖去新增实体或改夹具。
///
/// 固定夹具（owner 按首次出现顺序编号：`alpha`=1、`beta`=2）：
/// ```text
/// id=1 anvil   owner=alpha(1)
/// id=2 bandage owner=null
/// id=3 bolt    owner=beta(2)
/// ```
///
/// @param <T> 后端实体类型，必须实现 {@link Asset}
public abstract class CriteriaWriteAssociationConsistencyTest<T extends Asset> {

    protected static final long ANVIL_ID = 1L;

    protected static final long BANDAGE_ID = 2L;

    protected static final long BOLT_ID = 3L;

    protected static final String ALPHA = "alpha";

    protected static final String BETA = "beta";

    protected static final String ANVIL_NAME = "anvil";

    protected static final String BOLT_NAME = "bolt";

    /// `alpha` 的 `@Id`：夹具里 owner 按首次出现顺序编号，第一条非空关联就是 1。
    protected static final long ALPHA_OWNER_ID = 1L;

    /// `beta` 的 `@Id`。
    protected static final long BETA_OWNER_ID = 2L;

    /// 后端接缝：把「同一句条件更新 + 原始列读回」包成后端中立的两三个方法。
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

        /// 主键路径：`where(idRef()).eq(...)` 用。
        ///
        /// @return 根实体主键
        PathRef<T, Long> idRef();

        /// 关联外键的路径：`EntityPath.of(T::owner).get(Owner::getId)`。
        ///
        /// @return 根实体 → 关联 → 外键列的路径
        Path<T, Long> associationIdPath();

        /// 关联表普通列的路径：`EntityPath.of(T::owner).get(Owner::getName)`。
        ///
        /// @return 根实体 → 关联 → 关联表标量列的路径
        StringPath<T> associatedNamePath();

        /// 执行一条条件 update，返回受影响行数。
        ///
        /// @param work 在 step 上叠加 `set(...)` / `where(...)`
        /// @return 受影响行数
        int runUpdate(Consumer<UpdateSetStep<T>> work);

        /// 原始列读回：`select owner_id from ... where id = ?`。
        ///
        /// @param assetId 主键
        /// @return 外键值；列为 `NULL` 时返回 `null`
        Long foreignKeyOf(long assetId);

        /// 原始列读回：`select name from ... where id = ?`。
        ///
        /// @param assetId 主键
        /// @return 名称
        String nameOf(long assetId);

        /// 丢弃一级缓存/持久化上下文，保证后面的读回真的来自数据库。
        void clearPersistenceContext();
    }

    @BeforeEach
    final void seed() {
        fixture()
                .seedData(List.of(
                        new AssetFixture(ANVIL_ID, ANVIL_NAME, ALPHA),
                        new AssetFixture(BANDAGE_ID, "bandage", null),
                        new AssetFixture(BOLT_ID, BOLT_NAME, BETA)));
    }

    /// 后端接缝。
    ///
    /// @return 当前后端的夹具/读写适配
    protected abstract Fixture<T> fixture();

    @Test
    @DisplayName("条件写把关联外键置空：两后端都成功，原始列读回 NULL、关联路径 isNull 命中")
    void clearingForeignKeyThroughNestedAssociationIdWritesNull() {
        Fixture<T> fixture = fixture();
        int affected = fixture.runUpdate(step -> step.set(fixture.associationIdPath(), null)
                .where(fixture.idRef())
                .eq(ANVIL_ID));
        fixture.clearPersistenceContext();

        assertThat(affected).isEqualTo(1);
        assertThat(fixture.foreignKeyOf(ANVIL_ID))
                .as("set(关联.id, null) 必须把外键列写成 NULL，而不是跳过该列")
                .isNull();
        assertThat(ids(fixture.query()
                        .where(fixture.associatedNamePath())
                        .isNull()
                        .list()))
                .as("外键置空后该行必须命中关联路径 isNull")
                .contains(ANVIL_ID);
        assertThat(fixture.foreignKeyOf(BOLT_ID)).as("其它行不得被连带清空").isEqualTo(BETA_OWNER_ID);
    }

    @Test
    @DisplayName("条件写把关联外键改成另一行：原始列读回新 @Id，关联路径查询跟着换 owner")
    void writingForeignKeyThroughNestedAssociationIdUpdatesOwner() {
        Fixture<T> fixture = fixture();
        int affected = fixture.runUpdate(step -> step.set(fixture.associationIdPath(), BETA_OWNER_ID)
                .where(fixture.idRef())
                .eq(ANVIL_ID));
        fixture.clearPersistenceContext();

        assertThat(affected).isEqualTo(1);
        assertThat(fixture.foreignKeyOf(ANVIL_ID)).isEqualTo(BETA_OWNER_ID);
        assertThat(ids(fixture.query()
                        .where(fixture.associatedNamePath())
                        .eq(BETA)
                        .list()))
                .as("改后的行必须被新 owner 的关联路径查询命中")
                .contains(ANVIL_ID, BOLT_ID);
        assertThat(ids(fixture.query()
                        .where(fixture.associatedNamePath())
                        .eq(ALPHA)
                        .list()))
                .as("旧 owner 的关联路径查询不得再命中改过的行")
                .doesNotContain(ANVIL_ID);
    }

    @Test
    @DisplayName("SET 目标是关联表的普通列：两后端都 fail-fast，且本表列与外键都没被写")
    void settingReferencedScalarIsRejectedAndLeavesRowUntouched() {
        Fixture<T> fixture = fixture();

        assertThatThrownBy(() -> fixture.runUpdate(step -> step.set(fixture.associatedNamePath(), "hacked")
                        .where(fixture.idRef())
                        .eq(ANVIL_ID)))
                .as("关联表的列不是本表列，不能渲染成本表 SET")
                .isInstanceOf(UnsupportedOperationException.class);

        fixture.clearPersistenceContext();
        assertThat(fixture.foreignKeyOf(ANVIL_ID)).as("被拒的写不得改动外键").isEqualTo(ALPHA_OWNER_ID);
        assertThat(fixture.nameOf(ANVIL_ID)).as("被拒的写不得因为关联表和本表同名列而悄悄写进本表 name").isEqualTo(ANVIL_NAME);
        assertThat(ids(fixture.query()
                        .where(fixture.associatedNamePath())
                        .eq(ALPHA)
                        .list()))
                .contains(ANVIL_ID);
    }

    private List<Long> ids(List<T> rows) {
        return rows.stream().map(Asset::id).toList();
    }
}
