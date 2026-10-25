package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
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
/// 断言一律用「相对关系 + 读回值」，**不钉具体号段**：`delete from` / `truncate` 之后自增序列
/// 是否重置属方言实现细节（H2 与 MySQL 不同，且随版本变），钉死 `1,2,3` 只可能在其中一边成立。
/// 这不是宽容，而是把「号段从几开始」这件与框架无关的事实排除在契约之外。
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

        List<String> inserts = statements("insert into generated_item");
        assertThat(inserts).hasSize(1);
        String insert = inserts.getFirst();
        // 「框架不碰主键」是这条契约的守护目标，但判据不能写成「列清单里没有 id」——那是方言形态，
        // 两种库里不一样：真 MySQL 下发 `(name) values (?)`，H2 下发 `(name, id) values (?, default)`。
        // 真正的判据是**绑定形态**：自增列不许被绑成一个参数值（`values (?, ?)`，哪怕绑的是 null，
        // MySQL / H2 都会把 null 当「请发号」照样发号，光看「插入成功 + id 被回填」发现不了）。
        assertThat(insertColumns(insert)).as("列清单里必须有业务列").contains("name");
        assertThat(countPlaceholders(insert))
                .as("只允许绑定业务列的值（1 个占位符），自增列不得被绑定。实际语句: %s", insert)
                .isEqualTo(1);
        if (insertColumns(insert).contains("id")) {
            assertThat(insert)
                    .as("列清单带 id 时，它必须以 `default` 由数据库发号。实际语句: %s", insert)
                    .contains("default");
        }

        GeneratedItemEntity reloaded = reload(row.id());
        assertThat(reloaded).as("回填的 id 必须真能在库里定位到同一行").isNotNull();
        assertThat(reloaded.name()).isEqualTo("alpha");
    }

    @Test
    @DisplayName("insert：两次独立 insert 各自取号，递增且互不相同")
    void sequentialInsertsAdvanceTheGeneratedId() {
        GeneratedItemEntity first = new GeneratedItemEntity("first");
        insert(first);
        GeneratedItemEntity second = new GeneratedItemEntity("second");
        insert(second);

        // 逐条 insert 走的是各自的 persist + 取号路径，与 insertAll 的批量路径不是同一段代码
        assertThat(second.id()).as("第二次取号必须前进（同号说明取号被缓存或复用）").isGreaterThan(first.id());
        GeneratedItemEntity reloadedSecond = reload(second.id());
        assertThat(reloadedSecond).as("第二条必须真的落库").isNotNull();
        assertThat(reloadedSecond.name()).isEqualTo("second");
    }

    @Test
    @DisplayName("insertAll：每条都拿到自己的主键，且序列递增")
    void insertAllAssignsDistinctIncreasingIds() {
        GeneratedItemEntity first = new GeneratedItemEntity("first");
        GeneratedItemEntity second = new GeneratedItemEntity("second");

        insertAll(List.of(first, second));

        assertThat(first.id()).isNotNull();
        assertThat(second.id()).as("第二条必须是另一号（同号说明回填被覆盖），且递增").isNotNull().isGreaterThan(first.id());

        GeneratedItemEntity reloadedFirst = reload(first.id());
        GeneratedItemEntity reloadedSecond = reload(second.id());
        assertThat(reloadedFirst).as("第一条必须真的落库").isNotNull();
        assertThat(reloadedSecond).as("第二条必须真的落库").isNotNull();
        assertThat(reloadedFirst.name()).isEqualTo("first");
        assertThat(reloadedSecond.name()).isEqualTo("second");
    }

    @Test
    @DisplayName("insert：删掉一行后继续插入，生成的号继续前进（不复用已删的号）")
    void insertAfterDeleteKeepsAdvancingTheGeneratedId() {
        GeneratedItemEntity first = new GeneratedItemEntity("alpha");
        insert(first);
        GeneratedItemEntity second = new GeneratedItemEntity("beta");
        insert(second);

        support().inTransaction(entityManager -> entityManager
                .createQuery("delete from GeneratedItemEntity where id = :id")
                .setParameter("id", second.id())
                .executeUpdate());
        support().clear();

        GeneratedItemEntity third = new GeneratedItemEntity("gamma");
        insert(third);

        // 这条守的是「Hibernate 的 IDENTITY 取号 + 回填」这条路径，不是「框架自己不复用号」——
        // 框架在这条路径上只调 `em.persist`，号由数据库发（「不写 id」这一点由 INSERT 列清单那条
        // 用例覆盖）。价值在于：号一旦复用，任何「按 id 记下的引用」就指向了另一行，而这只有
        // 「删了再插」才暴露（真 MySQL 的 AUTO_INCREMENT 单调前进，H2 也只在显式 `restart` 后回退，
        // 所以断言两边都成立）。
        assertThat(third.id()).as("已删掉的号不得被复用").isNotEqualTo(first.id()).isNotEqualTo(second.id());
        assertThat(third.id()).isGreaterThan(first.id());
        assertThat(reload(second.id())).as("被删的行确实不在了").isNull();
        GeneratedItemEntity reloadedThird = reload(third.id());
        assertThat(reloadedThird).as("第三行必须还在").isNotNull();
        assertThat(reloadedThird.name()).isEqualTo("gamma");
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

        GeneratedItemEntity reloaded = reload(row.id());
        assertThat(reloaded).as("行必须还在（update 不得删行）").isNotNull();
        assertThat(reloaded.name()).isEqualTo("after");
        // count(*) 的返回类型由方言决定（H2 给 Long，MySQL 可能给 BigInteger），统一按 Number 取
        long rowCount = support().inTransaction(() -> ((Number) support()
                        .entityManager()
                        .createNativeQuery("select count(*) from generated_item")
                        .getSingleResult())
                .longValue());
        assertThat(rowCount).as("update 不得变成插入").isEqualTo(1L);
    }

    @Test
    @DisplayName("查询：按回填的主键能取回行；按 name 排序时列序与主键序分裂")
    void queryFindsRowsByGeneratedId() {
        // 名字的字典序（alpha < mike < zulu）刻意与插入顺序（zulu, alpha, mike）相反。
        // 主键由插入顺序发号，所以「按 id 排」的期望值与库的自然返回顺序完全重合，只有按 name 排
        // 才能让 ORDER BY 掉不掉在结果上分开（第一版正是按 id 排，断言恒真）。
        GeneratedItemEntity zulu = new GeneratedItemEntity("zulu");
        GeneratedItemEntity alpha = new GeneratedItemEntity("alpha");
        GeneratedItemEntity mike = new GeneratedItemEntity("mike");
        insertAll(List.of(zulu, alpha, mike));
        support().clear();

        // 期望值在内存里现算（Java 的字典序），不写死常量：写死的 `[alpha, mike, zulu]` 会把
        // 「数据库的 collation 怎么排」偷偷钉进用例，夹具一旦出现大小写混合或重音字符就会随方言
        // 失真。与其它断言同一口径：差分 oracle 给正确性，手算常量只钉「列序 ≠ 主键序」这一事实。
        assertThat(names(query().orderBy(GeneratedItemEntity::name).asc().list()))
                .as("按 name 升序：与插入顺序（也是主键序）不同，ORDER BY 丢了就会红")
                .isEqualTo(Stream.of(zulu, alpha, mike)
                        .map(GeneratedItemEntity::name)
                        .sorted()
                        .toList())
                .isNotEqualTo(names(List.of(zulu, alpha, mike)));

        assertThat(ids(query().where(GeneratedItemEntity::id).eq(alpha.id()).list()))
                .as("按回填的主键等值查询")
                .containsExactly(alpha.id());

        // 主键排序也钉一条：用 desc，期望是 asc 的逆序，同样不与自然返回顺序重合
        List<Long> ascendingIds = Stream.of(zulu, alpha, mike)
                .map(GeneratedItemEntity::id)
                .sorted()
                .toList();
        assertThat(ids(query().orderBy(GeneratedItemEntity::id).desc().list()))
                .as("按主键降序返回")
                .containsExactlyElementsOf(ascendingIds.reversed());
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

    private EntityQuery<GeneratedItemEntity> query() {
        return operations().query();
    }

    private void resetRows() {
        support().truncate("GeneratedItemEntity");
        support().clear();
        support().clearSql();
    }

    private static List<Long> ids(List<GeneratedItemEntity> rows) {
        return rows.stream().map(GeneratedItemEntity::id).toList();
    }

    private static List<String> names(List<GeneratedItemEntity> rows) {
        return rows.stream().map(GeneratedItemEntity::name).toList();
    }

    /// 取 `insert into <table> ( ... )` 括号里的列清单。
    private static List<String> insertColumns(String statement) {
        int open = statement.indexOf('(');
        int close = statement.indexOf(')', open + 1);
        return Stream.of(statement.substring(open + 1, close).split(","))
                .map(String::trim)
                .toList();
    }

    /// 数一条语句里绑定了多少个参数（`?` 的个数）。
    private static long countPlaceholders(String statement) {
        return statement.chars().filter(character -> character == '?').count();
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
