package io.github.nextentity.tests.jdbc.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.tests.jdbc.JdbcItemSupport;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

/// 评审发现 R21（引用/保留字标识符必须可用）：JPA 的注解里用反引号包住标识符表示「数据库标识符，原样、需要引用」
/// （`` @Column(name = "`desc`") ``），Hibernate 会按方言渲染成 `"desc"`（H2/PG）或 `` `desc` ``（MySQL）。
/// 当前 JDBC 后端把名字原样拼进 SQL，反引号会变成 SQL 文本的一部分，查询直接失败。
///
/// 本用例按「引用后应长什么样」写，不依赖实现细节：注解里是引用形态 → JDBC 侧同一实体必须能建表、读写、查询、关联。
/// 引用形态采用 JPA 可移植的**反引号标记**（Hibernate 的 quoted identifier 语法），断言只看行为不看 SQL 文本。
///
/// 复跑：`bash tmp/scripts/mvnl.sh -pl nextentity-tests-jdbc -am test -Dtest=ReviewR21QuotedIdentifierTest`
class ReviewR21QuotedIdentifierTest {

    @Test
    void quotedTableAndColumnsWorkForQueryAndCrud() {
        try (JdbcItemSupport support = new JdbcItemSupport("review_r21_order")) {
            support.execute("""
                    create table review_r21_order (
                        id bigint primary key,
                        "desc" varchar(32),
                        "order" int,
                        "select" varchar(32),
                        "MixedCase" varchar(32),
                        "version" bigint not null default 0
                    )
                    """);

            var operations = new JdbcEntityOperationsFactory(support.dataSource()).operations(QuotedOrder.class);
            operations.insert(newOrder(1L, "alpha", 10, "one", "Mixed-1"));
            operations.insert(newOrder(2L, "beta", 20, "two", "Mixed-2"));

            assertThat(operations.query().list()).as("引用表 + 引用列的实体查询").hasSize(2);
            assertThat(operations
                            .query()
                            .where(Path.of(QuotedOrder::getDesc))
                            .eq("alpha")
                            .list())
                    .as("WHERE 引用保留字列 `desc`")
                    .extracting(QuotedOrder::getId)
                    .containsExactly(1L);
            assertThat(operations
                            .query()
                            .orderBy(Path.of(QuotedOrder::getOrderValue))
                            .desc()
                            .list())
                    .as("ORDER BY 引用保留字列 `order`")
                    .extracting(QuotedOrder::getId)
                    .containsExactly(2L, 1L);
            PathRef<QuotedOrder, String> descRef = QuotedOrder::getDesc;
            assertThat(operations
                            .query()
                            .select(
                                    Path.of(descRef),
                                    Path.of(QuotedOrder::getId).count())
                            .groupBy(descRef)
                            .having(Path.of(QuotedOrder::getId).count().gt(0L))
                            .orderBy(descRef)
                            .asc()
                            .list())
                    .as("GROUP BY / HAVING / ORDER BY 引用列")
                    .hasSize(2);

            QuotedOrder changed =
                    operations.query().where(Path.of(QuotedOrder::getId)).eq(1L).first();
            changed.setDesc("changed");
            changed.setSelectValue("changed");
            operations.update(changed);
            assertThat(operations
                            .query()
                            .where(Path.of(QuotedOrder::getId))
                            .eq(1L)
                            .first()
                            .getSelectValue())
                    .as("UPDATE 的 SET 引用列 + WHERE 引用 id")
                    .isEqualTo("changed");
        }
    }

    @Test
    void quotedReservedTableNameWorks() {
        try (JdbcItemSupport support = new JdbcItemSupport("review_r21_table")) {
            support.execute("drop table if exists \"order\"");
            support.execute("create table \"order\" (id bigint primary key, \"desc\" varchar(32))");

            var operations = new JdbcEntityOperationsFactory(support.dataSource()).operations(ReservedTable.class);
            ReservedTable row = new ReservedTable();
            row.setId(1L);
            row.setDesc("a");
            operations.insert(row);

            assertThat(operations.query().list()).as("引用保留字表名 `order`").hasSize(1);
        }
    }

    @Test
    void quotedJoinColumnWorks() {
        try (JdbcItemSupport support = new JdbcItemSupport("review_r21_join")) {
            support.execute("create table review_r21_owner (id bigint primary key, \"select\" varchar(32))");
            support.execute(
                    "create table review_r21_asset (id bigint primary key, \"desc\" varchar(32), \"select\" bigint)");
            support.execute("insert into review_r21_owner (id, \"select\") values (1, 'alpha'), (2, 'beta')");
            support.execute(
                    "insert into review_r21_asset (id, \"desc\", \"select\") values (10, 'a1', 1), (11, 'a2', 2)");

            var operations = new JdbcEntityOperationsFactory(support.dataSource()).operations(QuotedAsset.class);
            EntityPath<QuotedAsset, QuotedOwner> ownerPath = EntityPath.of(QuotedAsset::getOwner);
            StringPath<QuotedAsset> ownerSelect = ownerPath.get(QuotedOwner::getSelect);
            assertThat(operations.query().where(ownerSelect).eq("alpha").list())
                    .as("LEFT JOIN 引用关联表列 `select`")
                    .extracting(QuotedAsset::getId)
                    .containsExactly(10L);
        }
    }

    @Test
    void quotedGeneratedKeyTableWorks() {
        try (JdbcItemSupport support = new JdbcItemSupport("review_r21_generated")) {
            support.execute(
                    "create table review_r21_generated (id bigint auto_increment primary key, \"desc\" varchar(32))");

            var operations = new JdbcEntityOperationsFactory(support.dataSource()).operations(QuotedGenerated.class);
            QuotedGenerated row = new QuotedGenerated();
            row.setDesc("first");
            operations.insert(row);

            assertThat(row.getId()).as("生成键在引用表上回填").isEqualTo(1L);
            assertThat(operations.query().list()).hasSize(1);
        }
    }

    /// Q3：引用形态的生成键列（注解 `` @Column(name = "`id`") `` + IDENTITY）。
    /// meta 剥引号后按裸名 `id` 回读 keys，渲染层再按 quoted 引用，两端不能互相污染。
    @Test
    void quotedGeneratedKeyColumnWorks() {
        try (JdbcItemSupport support = new JdbcItemSupport("review_r21_gencol")) {
            support.execute(
                    "create table \"gen_col_order\" (\"id\" bigint auto_increment primary key, \"desc\" varchar(32))");

            var operations = new JdbcEntityOperationsFactory(support.dataSource()).operations(QuotedGeneratedId.class);
            QuotedGeneratedId row = new QuotedGeneratedId();
            row.setDesc("first");
            operations.insert(row);

            assertThat(row.getId()).as("引用形态的生成键列回填").isEqualTo(1L);
            assertThat(operations.query().list()).hasSize(1);
        }
    }

    /// 对抗：标识符里含引号字符。JPA 侧（Hibernate 7.1.8）对 `` @Column(name = "`a\"b`") `` 生成的 DDL 是
    /// `create table ... ("a"b" ...)`——**不转义内层引号，SQL 直接非法**（实测 `HibernateQuoteProbe`）。
    /// 也就是说这条引用写法 JPA 后端本身不支持；为「两后端同一写法行为一致」，JDBC 侧不得静默尝试
    /// （会按方言转义成 `"a""b"` 从而比 JPA 多支持一种写法），应在 meta 期显式拒绝。
    @Test
    void quotedIdentifierContainingQuoteCharacterIsRejectedInsteadOfDivergingFromJpa() {
        try (JdbcItemSupport support = new JdbcItemSupport("review_r21_escape")) {
            support.execute("create table review_r21_escape (id bigint primary key, \"a\"\"b\" varchar(32))");

            assertThatThrownBy(() -> {
                        var operations =
                                new JdbcEntityOperationsFactory(support.dataSource()).operations(EmbeddedQuote.class);
                        operations.query().list();
                    })
                    .as("标识符内含引号字符时 JPA 侧不支持（Hibernate 生成非法 SQL），JDBC 必须同样拒绝")
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void unquotedIdentifiersRemainUnquoted() {
        try (JdbcItemSupport support = new JdbcItemSupport("review_r21_plain")) {
            support.execute("create table review_r21_plain (id bigint primary key, name varchar(32))");

            var operations = new JdbcEntityOperationsFactory(support.dataSource()).operations(PlainOrder.class);
            PlainOrder row = new PlainOrder();
            row.setId(1L);
            row.setName("plain");
            operations.insert(row);

            assertThat(operations
                            .query()
                            .where(Path.of(PlainOrder::getName))
                            .eq("plain")
                            .list())
                    .as("未引用的既有实体行为必须零变化")
                    .hasSize(1);
        }
    }

    private static QuotedOrder newOrder(Long id, String desc, int order, String select, String mixedCase) {
        QuotedOrder row = new QuotedOrder();
        row.setId(id);
        row.setDesc(desc);
        row.setOrderValue(order);
        row.setSelectValue(select);
        row.setMixedCase(mixedCase);
        return row;
    }

    @Entity
    @Table(name = "review_r21_order")
    static class QuotedOrder {
        @Id
        private Long id;

        @Column(name = "`desc`")
        private String desc;

        @Column(name = "`order`")
        private Integer orderValue;

        @Column(name = "`select`")
        private String selectValue;

        @Column(name = "`MixedCase`")
        private String mixedCase;

        @Version
        @Column(name = "`version`")
        private Long version;

        protected QuotedOrder() {}

        Long getId() {
            return id;
        }

        void setId(Long id) {
            this.id = id;
        }

        String getDesc() {
            return desc;
        }

        void setDesc(String desc) {
            this.desc = desc;
        }

        Integer getOrderValue() {
            return orderValue;
        }

        void setOrderValue(Integer orderValue) {
            this.orderValue = orderValue;
        }

        String getSelectValue() {
            return selectValue;
        }

        void setSelectValue(String selectValue) {
            this.selectValue = selectValue;
        }

        String getMixedCase() {
            return mixedCase;
        }

        void setMixedCase(String mixedCase) {
            this.mixedCase = mixedCase;
        }
    }

    @Entity
    @Table(name = "`order`")
    static class ReservedTable {
        @Id
        private Long id;

        @Column(name = "`desc`")
        private String desc;

        protected ReservedTable() {}

        Long getId() {
            return id;
        }

        void setId(Long id) {
            this.id = id;
        }

        String getDesc() {
            return desc;
        }

        void setDesc(String desc) {
            this.desc = desc;
        }
    }

    @Entity
    @Table(name = "review_r21_owner")
    static class QuotedOwner {
        @Id
        private Long id;

        @Column(name = "`select`")
        private String select;

        protected QuotedOwner() {}

        Long getId() {
            return id;
        }

        String getSelect() {
            return select;
        }
    }

    @Entity
    @Table(name = "review_r21_asset")
    static class QuotedAsset {
        @Id
        private Long id;

        @Column(name = "`desc`")
        private String desc;

        @ManyToOne
        @JoinColumn(name = "`select`")
        private QuotedOwner owner;

        protected QuotedAsset() {}

        Long getId() {
            return id;
        }

        QuotedOwner getOwner() {
            return owner;
        }
    }

    @Entity
    @Table(name = "review_r21_generated")
    static class QuotedGenerated {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "`desc`")
        private String desc;

        protected QuotedGenerated() {}

        Long getId() {
            return id;
        }

        void setDesc(String desc) {
            this.desc = desc;
        }
    }

    @Entity
    @Table(name = "`gen_col_order`")
    static class QuotedGeneratedId {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "`id`")
        private Long id;

        @Column(name = "`desc`")
        private String desc;

        protected QuotedGeneratedId() {}

        Long getId() {
            return id;
        }

        void setDesc(String desc) {
            this.desc = desc;
        }
    }

    @Entity
    @Table(name = "review_r21_escape")
    static class EmbeddedQuote {
        @Id
        private Long id;

        @Column(name = "`a\"b`")
        private String weird;

        protected EmbeddedQuote() {}

        Long getId() {
            return id;
        }

        void setId(Long id) {
            this.id = id;
        }

        String getWeird() {
            return weird;
        }

        void setWeird(String weird) {
            this.weird = weird;
        }
    }

    @Entity
    @Table(name = "review_r21_plain")
    static class PlainOrder {
        @Id
        private Long id;

        private String name;

        protected PlainOrder() {}

        Long getId() {
            return id;
        }

        void setId(Long id) {
            this.id = id;
        }

        String getName() {
            return name;
        }

        void setName(String name) {
            this.name = name;
        }
    }
}
