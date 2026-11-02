package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.jdbc.support.ItemStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 发版前审查探针：全部用「真库执行 vs 独立内存计算」的差分做 oracle，不钉手写 SQL 常量。
///
/// 覆盖面：分页窗口、条件改删（标量 / 嵌入叶子 / 外键 / 转换器列 / 枚举）、批量插入（混关联）、
/// 按实体更新、聚合反转换、分组计数。断言优先读**原始列**（`select *`），这样「写错列但读回恰好对」
/// 也逃不掉。
class JdbcWritePathRegressionTest {

    private static final String URL = "jdbc:h2:mem:review_probe;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private final JdbcDataSource dataSource = dataSource();
    private final EntityOperations<ProbeItem> items =
            new JdbcEntityOperationsFactory(dataSource).operations(ProbeItem.class);
    private final EntityOperations<MappedBaseItem> mappedBaseItems =
            new JdbcEntityOperationsFactory(dataSource).operations(MappedBaseItem.class);

    @BeforeEach
    void schema() throws SQLException {
        execute("drop table if exists probe_item");
        execute("drop table if exists probe_owner");
        execute("drop table if exists mapped_base_item");
        execute("create table probe_owner (id bigint primary key, name varchar(50))");
        execute("insert into probe_owner values (1, 'owner-1'), (2, 'owner-2')");
        execute("create table probe_item (id bigint primary key, name varchar(50), status varchar(20),"
                + " amount varchar(50), city varchar(50), zip int, owner_id bigint, version bigint)");
        execute("create table mapped_base_item (id bigint primary key, city varchar(50), zip int)");
    }

    // ---------------------------------------------------------------- 分页

    @Test
    @DisplayName("list(offset, limit) / slice 的窗口逐行对得上内存切片")
    void paginationSlicesAreContiguous() {
        seed(25);
        List<Long> all = ids(items.query().list());
        assertThat(all).containsExactlyElementsOf(range(1, 25));

        assertThat(ids(items.query().list(10))).containsExactlyElementsOf(range(1, 10));
        assertThat(ids(items.query().list(0, 10))).containsExactlyElementsOf(range(1, 10));
        assertThat(ids(items.query().list(10, 10))).containsExactlyElementsOf(range(11, 20));
        assertThat(ids(items.query().list(20, 10))).containsExactlyElementsOf(range(21, 25));
        assertThat(ids(items.query().list(20, 0))).containsExactlyElementsOf(range(21, 25));
        assertThat(ids(items.query().list(25, 10))).isEmpty();

        assertThat(ids(items.query().slice(20, 10).data())).containsExactlyElementsOf(range(21, 25));
        assertThat(items.query().slice(20, 10).total()).isEqualTo(25);
        assertThat(items.query().slice(25, 10).data()).isEmpty();
    }

    // ------------------------------------------------------- 条件更新（原始列 oracle）

    @Test
    @DisplayName("条件更新只写 SET 点名的列，其余列原值不变")
    void conditionalUpdateWritesOnlyNamedColumns() throws SQLException {
        seed(3);
        Map<String, Object> before = rawRow(2);

        int updated = items.update()
                .set(Path.of(ProbeItem::getName), "renamed")
                .where(Path.of(ProbeItem::getId))
                .eq(2L)
                .execute();

        assertThat(updated).isEqualTo(1);
        Map<String, Object> after = rawRow(2);
        assertThat(after).containsEntry("name", "renamed");
        assertThat(after).containsEntry("status", before.get("status"));
        assertThat(after).containsEntry("amount", before.get("amount"));
        assertThat(after).containsEntry("city", before.get("city"));
        assertThat(after).containsEntry("zip", before.get("zip"));
        assertThat(after).containsEntry("owner_id", before.get("owner_id"));
    }

    @Test
    @DisplayName("条件更新写嵌入叶子列：落到叶子列、不碰同名的其它列")
    void conditionalUpdateOnEmbeddedLeafWritesLeafColumn() throws SQLException {
        seed(3);

        int updated = items.update()
                .set(EntityPath.of(ProbeItem::getGeo).get(Geo::getCity), "Hangzhou")
                .where(Path.of(ProbeItem::getId))
                .eq(3L)
                .execute();

        assertThat(updated).isEqualTo(1);
        assertThat(rawRow(3)).containsEntry("city", "Hangzhou");
        assertThat(rawRow(1)).containsEntry("city", "city-1");
    }

    @Test
    @DisplayName("条件更新写外键列：值按目标 @Id 的类型绑定，置 null 即清外键")
    void conditionalUpdateOnForeignKeyWritesJoinColumn() throws SQLException {
        seed(3);

        int updated = items.update()
                .set(EntityPath.of(ProbeItem::getOwner).get(ProbeOwner::getId), 77L)
                .where(Path.of(ProbeItem::getId))
                .eq(2L)
                .execute();

        assertThat(updated).isEqualTo(1);
        assertThat(rawRow(2)).containsEntry("owner_id", 77L);

        int cleared = items.update()
                .set(EntityPath.of(ProbeItem::getOwner).get(ProbeOwner::getId), (Long) null)
                .where(Path.of(ProbeItem::getId))
                .eq(2L)
                .execute();

        assertThat(cleared).isEqualTo(1);
        assertThat(rawRow(2).get("owner_id")).isNull();
    }

    @Test
    @DisplayName("条件更新写枚举列与转换器列：库里存的是转换后的形态")
    void conditionalUpdateOnEnumAndConvertedColumns() throws SQLException {
        seed(3);

        items.update()
                .set(Path.of(ProbeItem::getStatus), ItemStatus.DONE)
                .set(Path.of(ProbeItem::getAmount), new BigDecimal("12.50"))
                .where(Path.of(ProbeItem::getId))
                .eq(1L)
                .execute();

        Map<String, Object> row = rawRow(1);
        assertThat(row).containsEntry("status", "DONE");
        assertThat(row).containsEntry("amount", "12.50");

        ProbeItem reloaded =
                items.query().where(Path.of(ProbeItem::getId)).eq(1L).single();
        assertThat(reloaded.getStatus()).isEqualTo(ItemStatus.DONE);
        assertThat(reloaded.getAmount()).isEqualByComparingTo("12.50");
    }

    @Test
    @DisplayName("条件更新的 WHERE 支持外键列与嵌入叶子列")
    void conditionalUpdateWhereOnNestedPaths() {
        seed(4);

        int byForeignKey = items.update()
                .set(Path.of(ProbeItem::getName), "by-fk")
                .where(EntityPath.of(ProbeItem::getOwner).get(ProbeOwner::getId))
                .eq(1L)
                .execute();
        assertThat(byForeignKey).isEqualTo(2);

        int byEmbeddedLeaf = items.update()
                .set(Path.of(ProbeItem::getName), "by-city")
                .where(EntityPath.of(ProbeItem::getGeo).get(Geo::getCity))
                .eq("city-3")
                .execute();
        assertThat(byEmbeddedLeaf).isEqualTo(1);
    }

    @Test
    @DisplayName("条件删除按外键列删掉对得上的行")
    void conditionalDeleteByForeignKey() {
        seed(4);

        int deleted = items.delete()
                .where(EntityPath.of(ProbeItem::getOwner).get(ProbeOwner::getId))
                .eq(1L)
                .execute();

        assertThat(deleted).isEqualTo(2);
        assertThat(ids(items.query().list())).containsExactly(2L, 4L);
    }

    // ------------------------------------------------------------- 写入

    @Test
    @DisplayName("insertAll 混关联（有/无关联对象）时一行都不能丢")
    void insertAllWithMixedAssociationKeepsEveryRow() throws SQLException {
        List<ProbeItem> batch = new ArrayList<>();
        batch.add(item(1, null));
        batch.add(item(2, new ProbeOwner(9L, "o")));
        batch.add(item(3, null));
        batch.add(item(4, new ProbeOwner(8L, "p")));

        items.insertAll(batch);

        assertThat(ids(items.query().list())).containsExactly(1L, 2L, 3L, 4L);
        assertThat(rawRow(2)).containsEntry("owner_id", 9L);
        assertThat(rawRow(4)).containsEntry("owner_id", 8L);
        assertThat(rawRow(1).get("owner_id")).isNull();
        assertThat(rawRow(3).get("owner_id")).isNull();
    }

    @Test
    @DisplayName("按实体 update 写回外键与嵌入列")
    void updateEntityWritesAssociationAndEmbedded() throws SQLException {
        seed(1);

        ProbeItem item = items.query().where(Path.of(ProbeItem::getId)).eq(1L).single();
        item.setOwner(new ProbeOwner(42L, "new"));
        item.setGeo(new Geo("Ningbo", 315000));
        items.update(item);

        Map<String, Object> row = rawRow(1);
        assertThat(row).containsEntry("owner_id", 42L);
        assertThat(row).containsEntry("city", "Ningbo");
        assertThat(row).containsEntry("zip", 315000);
    }

    // ------------------------------------------------------- 查询（条件 / 聚合）

    @Test
    @DisplayName("转换器列的比较值要先转成库侧形态再绑定")
    void whereOnConvertedColumnComparesConvertedValue() {
        seed(3);

        List<Long> found = ids(items.query()
                .where(Path.of(ProbeItem::getAmount))
                .eq(new BigDecimal("10"))
                .list());

        assertThat(found).containsExactly(1L);
    }

    @Test
    @DisplayName("聚合列读出时按实体类型反转换")
    void aggregateMaxOnConvertedColumn() {
        seed(3);

        BigDecimal max =
                items.query().select(Path.of(ProbeItem::getAmount).max()).single();

        assertThat(max).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("分组计数带关联条件：派生表里的 join 不能丢")
    void groupByCountWithJoinPredicate() {
        seed(4);

        long groups = items.query()
                .where(EntityPath.of(ProbeItem::getOwner).get(ProbeOwner::getId))
                .eq(1L)
                .groupBy(ProbeItem::getStatus)
                .count();

        assertThat(groups).isEqualTo(2);
    }

    // ------------------------------------------------------------- 版本与继承

    @Test
    @DisplayName("每次按实体 update 都自增 @Version 并把新版本写回实体")
    void versionBumpsOnEveryUpdate() throws SQLException {
        seed(1);
        ProbeItem item = items.query().where(Path.of(ProbeItem::getId)).eq(1L).single();
        assertThat(item.getVersion()).isEqualTo(0L);

        item.setName("a");
        items.update(item);
        assertThat(item.getVersion()).isEqualTo(1L);

        item.setName("b");
        items.update(item);
        assertThat(item.getVersion()).isEqualTo(2L);

        assertThat(rawRow(1)).containsEntry("version", 2L);
        assertThat(rawRow(1)).containsEntry("name", "b");
    }

    @Test
    @DisplayName("陈旧版本按实体 update 抛乐观锁异常，绝不静默覆盖新值")
    void staleVersionFailsInsteadOfOverwriting() throws SQLException {
        seed(1);
        ProbeItem stale = items.query().where(Path.of(ProbeItem::getId)).eq(1L).single();
        ProbeItem fresh = items.query().where(Path.of(ProbeItem::getId)).eq(1L).single();

        fresh.setName("fresh");
        items.update(fresh);

        stale.setName("stale");
        assertThatThrownBy(() -> items.update(stale)).isInstanceOf(OptimisticLockException.class);

        assertThat(rawRow(1)).containsEntry("name", "fresh");
    }

    @Test
    @DisplayName("@MappedSuperclass 父类声明的 @Embedded 字段同样扁平化并往返")
    void mappedSuperclassEmbeddedRoundTrip() throws SQLException {
        MappedBaseItem item = new MappedBaseItem();
        item.setId(1L);
        item.setGeo(new Geo("Wenzhou", 325000));
        mappedBaseItems.insert(item);

        assertThat(rawRowOf("mapped_base_item", 1L)).containsEntry("city", "Wenzhou");
        assertThat(mappedBaseItems
                        .query()
                        .where(Path.of(MappedBaseItem::getId))
                        .eq(1L)
                        .single()
                        .getGeo()
                        .getCity())
                .isEqualTo("Wenzhou");
    }

    // ------------------------------------------------------------- helpers

    private static JdbcDataSource dataSource() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(URL);
        source.setUser("sa");
        return source;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void seed(int rows) {
        List<ProbeItem> batch = new ArrayList<>();
        for (int i = 1; i <= rows; i++) {
            batch.add(item(i, new ProbeOwner((long) (i % 2 == 1 ? 1 : 2), "owner-" + i)));
        }
        items.insertAll(batch);
    }

    private static ProbeItem item(long id, ProbeOwner owner) {
        return new ProbeItem(
                id,
                "name-" + id,
                ItemStatus.values()[(int) (id % 3)],
                new BigDecimal(id * 10),
                new Geo("city-" + id, 100 + (int) id),
                owner);
    }

    private static List<Long> range(int from, int to) {
        List<Long> values = new ArrayList<>();
        for (long i = from; i <= to; i++) {
            values.add(i);
        }
        return values;
    }

    private static List<Long> ids(List<ProbeItem> rows) {
        return rows.stream().map(ProbeItem::getId).toList();
    }

    private Map<String, Object> rawRow(long id) throws SQLException {
        return rawRowOf("probe_item", id);
    }

    private Map<String, Object> rawRowOf(String table, long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("select * from " + table + " where id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as(table + " 里应有 id=" + id).isTrue();
                ResultSetMetaData meta = rows.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), rows.getObject(i));
                }
                return row;
            }
        }
    }

    // ------------------------------------------------------------- fixtures

    @Embeddable
    public static class Geo {

        private String city;

        private Integer zip;

        protected Geo() {}

        public Geo(String city, Integer zip) {
            this.city = city;
            this.zip = zip;
        }

        public String getCity() {
            return city;
        }

        public Integer getZip() {
            return zip;
        }
    }

    @Entity
    @Table(name = "probe_owner")
    public static class ProbeOwner {

        @Id
        private Long id;

        private String name;

        protected ProbeOwner() {}

        public ProbeOwner(long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    @Converter
    public static class AmountConverter implements AttributeConverter<BigDecimal, String> {

        @Override
        public String convertToDatabaseColumn(BigDecimal value) {
            return value == null ? null : value.toPlainString();
        }

        @Override
        public BigDecimal convertToEntityAttribute(String value) {
            return value == null ? null : new BigDecimal(value);
        }
    }

    @Entity
    @Table(name = "probe_item")
    public static class ProbeItem {

        @Id
        private Long id;

        private String name;

        @Enumerated(EnumType.STRING)
        private ItemStatus status;

        @Convert(converter = AmountConverter.class)
        private BigDecimal amount;

        @Embedded
        private Geo geo;

        @ManyToOne
        @JoinColumn(name = "owner_id")
        private ProbeOwner owner;

        @Version
        private Long version;

        protected ProbeItem() {}

        public ProbeItem(long id, String name, ItemStatus status, BigDecimal amount, Geo geo, ProbeOwner owner) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.amount = amount;
            this.geo = geo;
            this.owner = owner;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public ItemStatus getStatus() {
            return status;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public Geo getGeo() {
            return geo;
        }

        public void setGeo(Geo geo) {
            this.geo = geo;
        }

        public ProbeOwner getOwner() {
            return owner;
        }

        public void setOwner(ProbeOwner owner) {
            this.owner = owner;
        }

        public Long getVersion() {
            return version;
        }
    }

    /// `@MappedSuperclass` 父类持有 `@Embedded` 字段：子实体只声明主键，嵌入列来自父类。
    @MappedSuperclass
    public abstract static class BaseItem {

        @Embedded
        private Geo geo;

        public Geo getGeo() {
            return geo;
        }

        public void setGeo(Geo geo) {
            this.geo = geo;
        }
    }

    @Entity
    @Table(name = "mapped_base_item")
    public static class MappedBaseItem extends BaseItem {

        @Id
        private Long id;

        protected MappedBaseItem() {}

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}
