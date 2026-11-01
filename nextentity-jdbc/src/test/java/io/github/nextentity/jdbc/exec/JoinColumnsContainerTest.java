package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.CompositeJoinColumnItem;
import io.github.nextentity.jdbc.support.ContainerJoinColumnItem;
import io.github.nextentity.jdbc.support.SingularJoinColumnItem;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import java.util.List;
import org.junit.jupiter.api.Test;

/// `@JoinColumns(@JoinColumn(...))`（可重复注解的**容器**写法）必须与单列 `@JoinColumn` 同口径：
/// 单列容器的 `name` / `insertable` / `updatable` 照常生效；复合外键（>1 列）本层单列建模表达不了，
/// 元数据期显式 `UOE`（文案与 JPA 后端逐字一致）。
///
/// 3.2.0 的 `JdbcEntityRules#association` 用 `getAnnotation(JoinColumn.class)`：容器写法返回 null，
/// 于是 `@JoinColumns(@JoinColumn(name = "owner_ref", updatable = false))` 的列名回落默认命名
/// `owner_id`、只读外键照常进 INSERT/UPDATE；复合外键则被静默当单列映射——同一映射两种声明两种行为。
class JoinColumnsContainerTest {

    private static final JdbcMeta META = new JdbcMeta();
    private static final JdbcDialect H2 = new H2Dialect();
    private static final SnapshotOwner OWNER = new SnapshotOwner(9L, "nine");

    @Test
    void containerSingleColumnReadsNameAndFlagsLikeSingularForm() {
        JdbcEntityType<ContainerJoinColumnItem> container = META.entityType(ContainerJoinColumnItem.class);
        JdbcEntityType<SingularJoinColumnItem> singular = META.entityType(SingularJoinColumnItem.class);

        for (JdbcEntityType<?> type : List.of(container, singular)) {
            JdbcEntityAttribute owner = type.attribute("owner");
            assertThat(owner.joinColumn())
                    .as("%s：容器写法必须读 @JoinColumn(name)", type.type().getSimpleName())
                    .isEqualTo("owner_ref");
            assertThat(owner.isJoinColumnQuoted()).isFalse();
            assertThat(owner.isJoinColumnInsertable())
                    .as("%s：insertable 未声明 → 默认 true", type.type().getSimpleName())
                    .isTrue();
            assertThat(owner.isJoinColumnUpdatable())
                    .as("%s：updatable=false 的只读外键", type.type().getSimpleName())
                    .isFalse();
            assertThat(owner.isWritable())
                    .as(
                            "%s：只写 INSERT 也算可写（isWritable = insertable || updatable）",
                            type.type().getSimpleName())
                    .isTrue();
        }
    }

    @Test
    void insertAndUpdateStatementsTreatContainerSameAsSingularForm() {
        ContainerJoinColumnItem container = new ContainerJoinColumnItem(1L, "a", OWNER);
        SingularJoinColumnItem singular = new SingularJoinColumnItem(1L, "a", OWNER);

        assertThat(WriteSql.insert(META, META.entityType(ContainerJoinColumnItem.class), container, H2)
                        .sql())
                .isEqualTo("insert into container_join_column_item (id, name, owner_ref) values (?, ?, ?)");
        assertThat(WriteSql.insert(META, META.entityType(SingularJoinColumnItem.class), singular, H2)
                        .sql())
                .isEqualTo("insert into singular_join_column_item (id, name, owner_ref) values (?, ?, ?)");

        // updatable=false：只读外键不得进 UPDATE 的 SET（容器写法曾被静默写成 owner_id=?）
        assertThat(WriteSql.update(META, META.entityType(ContainerJoinColumnItem.class), container, H2)
                        .sql())
                .isEqualTo("update container_join_column_item set name=? where id=?");
        assertThat(WriteSql.update(META, META.entityType(SingularJoinColumnItem.class), singular, H2)
                        .sql())
                .isEqualTo("update singular_join_column_item set name=? where id=?");
    }

    @Test
    void compositeForeignKeyIsRejectedAtMetadata() {
        assertThatThrownBy(() -> META.entityType(CompositeJoinColumnItem.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("复合外键关联未支持: " + CompositeJoinColumnItem.class.getName()
                        + ".owner（@JoinColumns 列数=2，本层只建模单列外键）");
    }
}
