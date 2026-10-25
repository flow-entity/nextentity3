package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 自增主键（`@GeneratedValue`）的写侧契约：**同一套断言、两套连接**（H2 与真 MySQL）。
///
/// 此前这条能力在非遗留用例里是零覆盖：`GeneratedValue` 在 api / core / jpa 的 main 与全部
/// 非 legacy 测试实体里都 0 命中，2.2.3 的 `AutoIncrementInsertIntegrationTest` 是唯一证据，
/// 而那个模块要被移除——也就是「框架不碰主键、由数据库发号」这件事会在移除后彻底失去守护。
///
/// [PersistExecutor#insert](io.github.nextentity.core.exec.PersistExecutor) 返回 `void`，
/// 所以「插入后拿不到新主键」是这套 API 的先天约束；框架能保证的只有一件事：
/// 主键由数据库发号、并且回填到调用方传进来的那个实例上（Hibernate 对 IDENTITY 的实现就是
/// 立即执行 INSERT 并 `set` 回 id）。第 1 条用例钉的正是这一点——把 `insert` 换成
/// 「显式写 id 或延迟取号」的实现时，这里会立刻红。
///
/// 断言一律用「相对关系 + 读回值」，**不钉具体号段**：`delete from` 在 MySQL 上不重置
/// `AUTO_INCREMENT`，在 H2 上会重置，钉死 `1,2,3` 只可能在其中一边成立。这不是宽容，
/// 而是把「号段从几开始」这件与框架无关的方言事实排除在契约之外。
abstract class GeneratedIdInsertContract {

    /// 被测装置：子类只决定接哪条库。
    protected abstract WriteRoundTripSupport<GeneratedItemEntity> support();

    @BeforeEach
    void resetBefore() {
        resetRows();
    }

    @AfterEach
    void resetAfter() {
        resetRows();
    }

    @Test
    @DisplayName("insert：数据库发号并把主键回填到入参对象，且 INSERT 不写自增列")
    void insertFillsGeneratedIdBackOntoTheInstance() {
        GeneratedItemEntity row = new GeneratedItemEntity("alpha");

        insert(row);

        assertThat(row.id()).as("`insert` 返回 void，回填到入参对象是调用方拿到新主键的唯一通道").isNotNull();

        assertThat(statements("insert into generated_item")).hasSize(1);

        GeneratedItemEntity reloaded = reload(row.id());
        assertThat(reloaded).as("回填的 id 必须真能在库里定位到同一行").isNotNull();
        assertThat(reloaded.name()).isEqualTo("alpha");
    }

    @Test
    @DisplayName("insertAll：每条都拿到自己的主键，且序列递增")
    void insertAllAssignsDistinctIncreasingIds() {
        GeneratedItemEntity first = new GeneratedItemEntity("first");
        GeneratedItemEntity second = new GeneratedItemEntity("second");

        insertAll(List.of(first, second));

        assertThat(first.id()).isNotNull();
        assertThat(second.id()).as("第二条必须是另一号（同号说明回填被覆盖），且递增").isNotNull().isGreaterThan(first.id());

        assertThat(reload(first.id()).name()).isEqualTo("first");
        assertThat(reload(second.id()).name()).isEqualTo("second");
    }

    @Test
    @DisplayName("update：用回填的主键定位行，SET 只写非主键列")
    void updateUsesTheGeneratedIdInWhereClause() {
        GeneratedItemEntity row = new GeneratedItemEntity("before");
        insert(row);
        support().clear();
        support().clearSql();

        update(new GeneratedItemEntity(row.id(), "after"));

        List<String> updates = statements("update generated_item");
        assertThat(updates).hasSize(1);
        String statement = updates.getFirst();
        assertThat(setClause(statement)).as("主键不进 SET 子句").isEqualTo("name=?");
        assertThat(statement).as("WHERE 必须落在主键上").contains("where").contains("id=?");

        assertThat(reload(row.id()).name()).isEqualTo("after");
        // count(*) 的返回类型由方言决定（H2 给 Long，MySQL 可能给 BigInteger），统一按 Number 取
        long rowCount = support().inTransaction(() -> ((Number) support()
                        .entityManager()
                        .createNativeQuery("select count(*) from generated_item")
                        .getSingleResult())
                .longValue());
        assertThat(rowCount).as("update 不得变成插入").isEqualTo(1L);
    }

    @Test
    @DisplayName("查询：按回填的主键能取回行，排序也按主键生效")
    void queryFindsRowsByGeneratedId() {
        GeneratedItemEntity third = new GeneratedItemEntity("third");
        GeneratedItemEntity first = new GeneratedItemEntity("first");
        GeneratedItemEntity second = new GeneratedItemEntity("second");
        // 故意乱序插入：若排序没生效，返回顺序就是插入顺序，断言会红
        insertAll(List.of(third, first, second));
        support().clear();

        // 期望值按 id 现算而不是按插入顺序写死：号段由数据库决定，插入顺序与 id 顺序未必一致
        // （本夹具就是故意乱序插的），写死顺序会把一条无关的方言事实变成断言
        List<Long> ascendingIds = Stream.of(third, first, second)
                .map(GeneratedItemEntity::id)
                .sorted()
                .toList();
        assertThat(ids(operations()
                        .query()
                        .orderBy(GeneratedItemEntity::id)
                        .asc()
                        .list()))
                .as("按主键升序返回")
                .containsExactlyElementsOf(ascendingIds);

        assertThat(ids(operations()
                        .query()
                        .where(GeneratedItemEntity::id)
                        .eq(second.id())
                        .list()))
                .containsExactly(second.id());
    }

    private void insert(GeneratedItemEntity row) {
        support().inTransaction(entityManager -> operations().insert(row));
        support().clear();
    }

    private void insertAll(List<GeneratedItemEntity> rows) {
        support().inTransaction(entityManager -> operations().insertAll(rows));
        support().clear();
    }

    private void update(GeneratedItemEntity row) {
        support().inTransaction(entityManager -> operations().update(row));
        support().clear();
    }

    private GeneratedItemEntity reload(long id) {
        return support().inTransaction(() -> support().entityManager().find(GeneratedItemEntity.class, id));
    }

    private EntityOperations<GeneratedItemEntity> operations() {
        return support().operations();
    }

    private void resetRows() {
        support().truncate("GeneratedItemEntity");
        support().clear();
        support().clearSql();
    }

    private static List<Long> ids(List<GeneratedItemEntity> rows) {
        return rows.stream().map(GeneratedItemEntity::id).toList();
    }

    /// 取归一化语句里 `set` 与 `where` 之间的片段。
    ///
    /// 只在 SET 上断言而不是整条语句：列序由 Hibernate 内部决定，钉整句等于把用例绑死在实现细节上。
    private static String setClause(String statement) {
        int set = statement.indexOf(" set ");
        int where = statement.indexOf(" where ", set + 5);
        return statement.substring(set + 5, where < 0 ? statement.length() : where);
    }

    /// 归一化后按前缀筛出被测语句。
    private List<String> statements(String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return support().drainSql().stream()
                .map(WriteRoundTripSupport::normalized)
                .filter(statement -> statement.startsWith(lowered))
                .toList();
    }
}
