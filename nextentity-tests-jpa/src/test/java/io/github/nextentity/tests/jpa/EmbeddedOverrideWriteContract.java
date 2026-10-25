package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 嵌入写侧 + `@AttributeOverride` 的**同一套断言、两套连接**（H2 与真 MySQL）。
///
/// 为什么把断言只写一遍：容器通道与 H2 通道若各抄一份断言，两份会随时间漂移——一边改了期望值，
/// 另一边仍在断言旧语义，于是「真方言绿、H2 绿」都不再说明同一件事。这里子类只负责
/// 接哪条库（[EmbeddedOverrideWriteTest] 走 H2，`MySqlEmbeddedOverrideWriteIntegrationTest` 走容器），
/// 断言与夹具都在本类，漂移在编译期就不可能发生。
///
/// 守护的语义（此前非遗留用例零覆盖的两条）：
/// 1. **嵌入写侧**：`JpaPersistExecutor#setValues` 只跳过主键与关联，`@Embedded` 不是关联，
///    所以 `update` 会把整个可嵌入对象塞进 `CriteriaUpdate.set(...)`。Hibernate 7 会把它摊平成
///    叶子列（`set home_city=?,home_zone=?,...`），本类把「摊平」这件事钉住：语句层看叶子列、
///    结果层看重新查出来的值。若哪天换成「按列名拼 SQL」的实现、或 Hibernate 不再摊平，
///    两侧会同时红。
/// 2. **`@AttributeOverride`**：同一个 [PostalAddress] 在实体上出现两次、各自覆盖到不同列。
///    第 4/5 条用例是这条能力唯一有区分力的证据：两个 `@Embedded` 若都落到默认列名（或都落到
///    覆盖后的同一列），两条查询会返回同一批行；只有「各写各的列」时镜像夹具才会分裂成
///    `[1]` 与 `[2]` 两个不同答案。
///
/// 断言一律用「叶子列名片段 + 读回值」，不比整句：整句里的 SET 列序由 Hibernate 内部顺序决定，
/// 与本库语义无关，钉住它等于把用例绑死在实现细节上（既有的整句断言只用在列序稳定的简单实体上）。
abstract class EmbeddedOverrideWriteContract {

    /// 子类给出连接：H2 内存库或真 MySQL 容器。
    ///
    /// @return 写侧往返装置
    protected abstract WriteRoundTripSupport<ContactItemEntity> support();

    /// 夹具的内存副本：差分 oracle 的输入，与灌进库里的那两行逐字段相同。
    private List<ContactItemEntity> fixture = List.of();

    /// 每个用例前清表、丢受管状态、清语句账本：容器被同一 JVM 里多个类共享，H2 内存库则被
    /// 本类的多个用例共享，两者都需要「从空表开始」这条前提。
    @BeforeEach
    void resetBefore() {
        resetRows();
    }

    /// 复位（AGENTS.md：集成测试启动容器后须在 `@AfterEach` 复位被改状态）。
    @AfterEach
    void resetAfter() {
        resetRows();
    }

    @Test
    @DisplayName("update：两个嵌入的四个叶子列都进 SET，读回是新值")
    void updateFlattensBothEmbeddedsIntoLeafColumns() {
        insert(new ContactItemEntity(1L, "before", address("Beijing", "north"), address("Shanghai", "east")));
        support().clearSql();

        update(new ContactItemEntity(1L, "after", address("Chengdu", "south"), address("Wuhan", "west")));

        List<String> updates = statements("update contact_item");
        assertThat(updates).as("一次 update 只能下发一条 UPDATE").hasSize(1);
        String set = setClause(updates.getFirst());
        // 叶子列：`home_city` / `home_zone` 来自 home 的覆盖，`office_*` 来自 office 的覆盖
        assertThat(set)
                .as("嵌入属性必须摊平成叶子列：整块嵌入直接进 SET 会在真库上直接语法错误")
                .contains("home_city=?")
                .contains("home_zone=?")
                .contains("office_city=?")
                .contains("office_zone=?")
                .contains("name=?");

        // 结果层：语句层挡不住「写了两条 UPDATE 再覆盖回来」，读回值必须跟上
        ContactItemEntity reloaded = reload(1L);
        assertThat(reloaded.name()).isEqualTo("after");
        assertThat(reloaded.home().city()).isEqualTo("Chengdu");
        assertThat(reloaded.home().geo().zone()).isEqualTo("south");
        assertThat(reloaded.office().city()).isEqualTo("Wuhan");
        assertThat(reloaded.office().geo().zone()).isEqualTo("west");
    }

    @Test
    @DisplayName("update：嵌入置 null 时叶子列绑 null，另一侧嵌入原样保留")
    void updateWithNullEmbeddedWritesNullLeaves() {
        insert(new ContactItemEntity(1L, "before", address("Beijing", "north"), address("Shanghai", "east")));
        support().clearSql();

        update(new ContactItemEntity(1L, "after", null, address("Shanghai", "east")));

        String set = setClause(statements("update contact_item").getFirst());
        // 置 null 不是「跳过这一列」：跳过会让库里的旧值留下来，读回侧立刻能看出差别
        assertThat(set).contains("home_city=?").contains("home_zone=?");

        ContactItemEntity reloaded = reload(1L);
        assertThat(reloaded.home()).as("四个叶子列全为 null 时整块嵌入读回 null").isNull();
        assertThat(reloaded.office().city()).isEqualTo("Shanghai");
        assertThat(reloaded.office().geo().zone()).isEqualTo("east");
    }

    @Test
    @DisplayName("insert：四个覆盖后的列都真的写进表里")
    void insertWritesOverriddenColumns() {
        insert(new ContactItemEntity(1L, "row", address("Beijing", "north"), address("Shanghai", "east")));

        assertThat(statements("insert into contact_item"))
                .as("插入必须带覆盖后的列名，否则覆盖只对读侧生效")
                .isNotEmpty()
                .allSatisfy(statement -> assertThat(statement)
                        .contains("home_city")
                        .contains("home_zone")
                        .contains("office_city")
                        .contains("office_zone"));

        // 直接读列：ORM 读回值可能是同一实例的内存值，原生查询才证明列真的存在且有值
        assertThat(column("home_city", 1L)).isEqualTo("Beijing");
        assertThat(column("home_zone", 1L)).isEqualTo("north");
        assertThat(column("office_city", 1L)).isEqualTo("Shanghai");
        assertThat(column("office_zone", 1L)).isEqualTo("east");
    }

    @Test
    @DisplayName("查询：home.city 与 office.city 落在各自的覆盖列上，镜像夹具分裂成两个答案")
    void queryByHomeAndOfficeCityAreIndependent() {
        seedMirrored();

        assertThat(idsWhereHomeCity("Beijing")).as("home 一侧").containsExactly(1L);
        assertThat(idsWhereOfficeCity("Beijing"))
                .as("office 一侧：两个 @Embedded 若落到同一列，这里会跟着返回 [1]")
                .containsExactly(2L);
    }

    @Test
    @DisplayName("查询：两层嵌入的叶子（geo.zone）也按各自覆盖列解析")
    void queryByNestedZoneUsesOverriddenColumns() {
        seedMirrored();

        assertThat(ids(query().where(EntityPath.of(ContactItemEntity::home)
                                .get(PostalAddress::geo)
                                .get(GeoPoint::zone)
                                .eq("north"))
                        .orderBy(ContactItemEntity::id)
                        .asc()
                        .list()))
                .as("home.geo.zone = north")
                .containsExactly(1L);
        assertThat(ids(query().where(EntityPath.of(ContactItemEntity::office)
                                .get(PostalAddress::geo)
                                .get(GeoPoint::zone)
                                .eq("north"))
                        .orderBy(ContactItemEntity::id)
                        .asc()
                        .list()))
                .as("office.geo.zone = north")
                .containsExactly(2L);
    }

    @Test
    @DisplayName("查询：WHERE 与 ORDER BY 渲染的都是覆盖后的列名")
    void renderingUsesOverriddenColumnNames() {
        seedMirrored();
        support().clearSql();

        assertThat(idsWhereHomeCity("Beijing")).containsExactly(1L);

        String select = statements("select").getFirst();
        assertThat(select)
                .as("条件必须落在 home_city 上；落到 office_city 会返回另一行")
                .contains("home_city=?")
                .doesNotContain("office_city=?");

        support().clearSql();
        List<Long> byHomeCity =
                ids(query().orderBy(EntityPath.of(ContactItemEntity::home).get(PostalAddress::city))
                        .asc()
                        .list());
        // home.city：1 → Beijing，2 → Shanghai，按列值升序即 id 升序
        assertThat(byHomeCity).containsExactly(1L, 2L);
        assertThat(statements("select").getFirst())
                .as("ORDER BY 也要走覆盖列，否则排序结果与 WHERE 用的不是同一列")
                .contains("order by")
                .contains("home_city");
    }

    @Test
    @DisplayName("差分：四组条件在真库与内存筛选下结果一致")
    void queryResultsMatchInMemoryOracle() {
        seedMirrored();

        // 期望值从夹具现算（内存里按同样的规则筛一遍），而不是手写常量：
        // 「框架把嵌套路径编译到错的列」与「我手算错了」不会互相掩盖。
        for (String city : List.of("Beijing", "Shanghai", "Nowhere")) {
            assertThat(idsWhereHomeCity(city)).as("home.city = %s", city).isEqualTo(expectedWhereCity(true, city));
            assertThat(idsWhereOfficeCity(city)).as("office.city = %s", city).isEqualTo(expectedWhereCity(false, city));
        }
        for (String zone : List.of("north", "east", "south", "Nowhere")) {
            assertThat(idsWhereHomeZone(zone)).as("home.geo.zone = %s", zone).isEqualTo(expectedWhereZone(true, zone));
            assertThat(idsWhereOfficeZone(zone))
                    .as("office.geo.zone = %s", zone)
                    .isEqualTo(expectedWhereZone(false, zone));
        }
    }

    /// 镜像夹具：两行的 home / office 城市与 zone 互换，于是「按 home 查」与「按 office 查」的答案不同。
    ///
    /// 城市与 zone 都要镜像：只镜像城市时，两行的 home zone 会同时是 north，
    /// `home.geo.zone = north` 就会返回两行，两层嵌入的断言失去区分力（第一版夹具正是这么错的）。
    private void seedMirrored() {
        fixture = List.of(
                new ContactItemEntity(1L, "first", address("Beijing", "north"), address("Shanghai", "south")),
                new ContactItemEntity(2L, "second", address("Shanghai", "east"), address("Beijing", "north")));
        insert(fixture.toArray(ContactItemEntity[]::new));
    }

    private List<Long> idsWhereHomeCity(String city) {
        return ids(query().where(EntityPath.of(ContactItemEntity::home)
                        .get(PostalAddress::city)
                        .eq(city))
                .orderBy(ContactItemEntity::id)
                .asc()
                .list());
    }

    private List<Long> idsWhereOfficeCity(String city) {
        return ids(query().where(EntityPath.of(ContactItemEntity::office)
                        .get(PostalAddress::city)
                        .eq(city))
                .orderBy(ContactItemEntity::id)
                .asc()
                .list());
    }

    private List<Long> idsWhereHomeZone(String zone) {
        return ids(query().where(EntityPath.of(ContactItemEntity::home)
                        .get(PostalAddress::geo)
                        .get(GeoPoint::zone)
                        .eq(zone))
                .orderBy(ContactItemEntity::id)
                .asc()
                .list());
    }

    private List<Long> idsWhereOfficeZone(String zone) {
        return ids(query().where(EntityPath.of(ContactItemEntity::office)
                        .get(PostalAddress::geo)
                        .get(GeoPoint::zone)
                        .eq(zone))
                .orderBy(ContactItemEntity::id)
                .asc()
                .list());
    }

    /// 差分 oracle：内存里按城市筛一遍夹具。
    private List<Long> expectedWhereCity(boolean home, String city) {
        return fixture.stream()
                .filter(row -> city.equals((home ? row.home() : row.office()).city()))
                .map(ContactItemEntity::id)
                .sorted()
                .toList();
    }

    /// 差分 oracle：内存里按两层嵌入的叶子（zone）筛一遍夹具。
    private List<Long> expectedWhereZone(boolean home, String zone) {
        return fixture.stream()
                .filter(row ->
                        zone.equals((home ? row.home() : row.office()).geo().zone()))
                .map(ContactItemEntity::id)
                .sorted()
                .toList();
    }

    private static List<Long> ids(List<ContactItemEntity> rows) {
        return rows.stream().map(ContactItemEntity::id).toList();
    }

    private static PostalAddress address(String city, String zone) {
        return new PostalAddress(city, zone);
    }

    private void insert(ContactItemEntity... rows) {
        support().inTransaction(entityManager -> operations().insertAll(List.of(rows)));
        support().clear();
    }

    private void update(ContactItemEntity row) {
        support().inTransaction(entityManager -> operations().update(row));
        support().clear();
    }

    /// 从库里重新读一行（`clear()` 之后无受管状态，读到的必然是库里的值）。
    ///
    /// 用无参 lambda 而不是 `em -> em.find(...)`：后者与 `inTransaction(Consumer<EntityManager>)`
    /// 的 void 重载兼容，编译会选中 Consumer 重载并丢掉返回值。
    private ContactItemEntity reload(long id) {
        return support().inTransaction(() -> support().entityManager().find(ContactItemEntity.class, id));
    }

    /// 直接读原生列：证明覆盖后的列名真的落在库里，而不只是出现在语句文本里。
    private Object column(String column, long id) {
        return support().inTransaction(() -> support()
                .entityManager()
                .createNativeQuery("select " + column + " from contact_item where id = ?1")
                .setParameter(1, id)
                .getSingleResult());
    }

    private EntityOperations<ContactItemEntity> operations() {
        return support().operations();
    }

    private EntityQuery<ContactItemEntity> query() {
        return operations().query();
    }

    private void resetRows() {
        support().truncate("ContactItemEntity");
        support().clear();
        support().clearSql();
    }

    /// 取归一化语句里 `set` 与 `where` 之间的片段。
    ///
    /// 只断言 SET 而不是整句：`where id=?` 若将来被改写（例如带版本条件），整句相等会假红，
    /// 而本类要钉的是「嵌入摊平成哪些列」，与 WHERE 的形状无关。
    ///
    /// @param statement 归一化后的语句
    /// @return SET 子句片段
    private static String setClause(String statement) {
        int set = statement.indexOf(" set ");
        int where = statement.indexOf(" where ", set + 5);
        return statement.substring(set + 5, where < 0 ? statement.length() : where);
    }

    /// 归一化后按前缀筛出被测语句。
    ///
    /// @param prefix 语句前缀（大小写不敏感）
    /// @return 归一化后的语句
    private List<String> statements(String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return support().drainSql().stream()
                .map(WriteRoundTripSupport::normalized)
                .filter(statement -> statement.startsWith(lowered))
                .toList();
    }
}
