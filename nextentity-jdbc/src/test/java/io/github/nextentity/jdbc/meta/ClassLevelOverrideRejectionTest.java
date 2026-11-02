package io.github.nextentity.jdbc.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import jakarta.persistence.AssociationOverride;
import jakarta.persistence.AssociationOverrides;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 类级（TYPE）`@AttributeOverride` / `@AssociationOverride` 是 JPA 合法形态（点号路径指向嵌入属性，
/// Hibernate 支持），但本后端只建模**成员级**覆盖：元数据期必须 fail-fast 拒绝。
///
/// 静默降级的后果不是「少个功能」：列名会回落到默认列名，按 JPA 语义建的表（只有 `town` 列）
/// 在 JDBC 上首次写入就报 `Column "CITY" not found`——报错点离原因很远，且两后端行为分叉。
///
/// 成员级覆盖是控制组：它必须继续工作，否则说明拒绝写宽了。
class ClassLevelOverrideRejectionTest {

    private static final String MESSAGE = "类级 @AttributeOverride/@AssociationOverride 未支持: ";

    private final JdbcDataSource dataSource = dataSource();

    @BeforeEach
    void schema() throws SQLException {
        execute("drop table if exists field_override_item");
        execute("create table field_override_item (id bigint primary key, town varchar(50), zip int)");
    }

    @Test
    @DisplayName("类级 @AttributeOverride 在元数据期被拒")
    void classLevelAttributeOverrideRejected() {
        assertThatThrownBy(() -> operations(ClassAttributeOverrideItem.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(message(ClassAttributeOverrideItem.class));
    }

    @Test
    @DisplayName("类级 @AttributeOverrides 容器形式同样被拒")
    void classLevelAttributeOverridesContainerRejected() {
        assertThatThrownBy(() -> operations(ContainerAttributeOverridesItem.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(message(ContainerAttributeOverridesItem.class));
    }

    @Test
    @DisplayName("类级 @AssociationOverride 在元数据期被拒")
    void classLevelAssociationOverrideRejected() {
        assertThatThrownBy(() -> operations(AssociationOverrideItem.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(message(AssociationOverrideItem.class));
    }

    @Test
    @DisplayName("类级 @AssociationOverrides 容器形式同样被拒")
    void classLevelAssociationOverridesContainerRejected() {
        assertThatThrownBy(() -> operations(ContainerAssociationOverridesItem.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(message(ContainerAssociationOverridesItem.class));
    }

    @Test
    @DisplayName("@MappedSuperclass 父类上的类级覆盖也被拒，文案报父类全名")
    void mappedSuperclassLevelOverrideRejected() {
        assertThatThrownBy(() -> operations(MappedSuperclassChildItem.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(message(OverrideBase.class));
    }

    @Test
    @DisplayName("控制组：成员级 @AttributeOverride 继续生效，写进 town 列")
    void fieldLevelOverrideStillWorks() throws SQLException {
        operations(FieldOverrideItem.class).insert(new FieldOverrideItem(1L, new Address("Hangzhou", 310000)));

        assertThat(rawRow("field_override_item", 1L)).containsEntry("town", "Hangzhou");
    }

    // ------------------------------------------------------------- helpers

    private static String message(Class<?> declaring) {
        return MESSAGE + declaring.getName() + "（请声明到嵌入字段或关联字段上）";
    }

    private <T> EntityOperations<T> operations(Class<T> type) {
        return new JdbcEntityOperationsFactory(dataSource).operations(type);
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:class_level_override;MODE=MySQL;DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        return source;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Map<String, Object> rawRow(String table, long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("select * from " + table + " where id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as(table + " 里应有 id=" + id).isTrue();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                    row.put(rows.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT), rows.getObject(i));
                }
                return row;
            }
        }
    }

    // ------------------------------------------------------------- fixtures

    @Embeddable
    public static class Address {

        private String city;

        private Integer zip;

        protected Address() {}

        public Address(String city, Integer zip) {
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

    /// 控制组夹具：覆盖声明在 `@Embedded` 字段上（成员级），必须继续生效。
    @Entity
    @Table(name = "field_override_item")
    public static class FieldOverrideItem {

        @Id
        private Long id;

        @Embedded
        @AttributeOverride(name = "city", column = @Column(name = "town"))
        private Address address;

        protected FieldOverrideItem() {}

        public FieldOverrideItem(Long id, Address address) {
            this.id = id;
            this.address = address;
        }

        public Long getId() {
            return id;
        }

        public Address getAddress() {
            return address;
        }
    }

    @Entity
    @Table(name = "class_attribute_override_item")
    @AttributeOverride(name = "address.city", column = @Column(name = "town"))
    public static class ClassAttributeOverrideItem {

        @Id
        private Long id;

        @Embedded
        private Address address;

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "container_attribute_overrides_item")
    @AttributeOverrides(@AttributeOverride(name = "address.city", column = @Column(name = "town")))
    public static class ContainerAttributeOverridesItem {

        @Id
        private Long id;

        @Embedded
        private Address address;

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "association_override_item")
    @AssociationOverride(name = "address.city", joinColumns = @JoinColumn(name = "town"))
    public static class AssociationOverrideItem {

        @Id
        private Long id;

        @Embedded
        private Address address;

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "container_association_overrides_item")
    @AssociationOverrides(@AssociationOverride(name = "address.city", joinColumns = @JoinColumn(name = "town")))
    public static class ContainerAssociationOverridesItem {

        @Id
        private Long id;

        @Embedded
        private Address address;

        public Long getId() {
            return id;
        }
    }

    /// 覆盖声明在 `@MappedSuperclass` 父类上：只有 `fields()` 的逐层循环能看到它。
    @MappedSuperclass
    @AttributeOverrides(@AttributeOverride(name = "address.city", column = @Column(name = "town")))
    public abstract static class OverrideBase {

        @Embedded
        private Address address;

        public Address getAddress() {
            return address;
        }
    }

    @Entity
    @Table(name = "mapped_superclass_child_item")
    public static class MappedSuperclassChildItem extends OverrideBase {

        @Id
        private Long id;

        public Long getId() {
            return id;
        }
    }
}
