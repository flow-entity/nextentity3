package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.jpa.entity.PropertyAccessFlags;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 写侧只读判定与 `@Embedded` 取值都要跟着 **access type** 走：`@Access(PROPERTY)` 实体把
/// `@Column` / `@Embedded` 写在 getter 上，字段上是空的。
///
/// USAGE §13.4.3 只把「只支持字段访问」列为 JDBC 的限制（JPA 不受此限），所以 JPA 侧按 getter
/// 声明的只读列与嵌入属性也必须照常工作。
class JpaPersistPropertyAccessTest extends JpaTestSupport {

    @BeforeEach
    void seedPropertyAccessFlags() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from PropertyAccessFlags").executeUpdate();
            entityManager.persist(new PropertyAccessFlags(
                    1L, "orig", "orig-locked", new PropertyAccessFlags.PropertyContact("orig-city")));
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("实体 update：getter 上声明的只读列不进 SET，getter 映射的嵌入列照常写")
    void updateFollowsPropertyAccessDeclarations() {
        inTx(entityManager -> {
            new JpaPersistExecutor<>(entityManager, meta(), PropertyAccessFlags.class)
                    .update(new PropertyAccessFlags(
                            1L, "renamed", "hacked", new PropertyAccessFlags.PropertyContact("Ningbo")));
            return null;
        });

        List<String> sql = normalized(drainSql());
        assertThat(sql).hasSize(1);
        String update = sql.getFirst();
        assertThat(update).startsWith("update property_access_flags ");
        assertThat(update).contains("label=?").contains("city=?");
        assertThat(update).as("getter 上的 @Column(updatable=false) 同样不进 SET").doesNotContain("locked_name");

        PropertyAccessFlags read = inTx(entityManager -> entityManager.find(PropertyAccessFlags.class, 1L));
        assertThat(read.getLabel()).isEqualTo("renamed");
        assertThat(read.getContact().getCity()).isEqualTo("Ningbo");
        assertThat(read.getLockedName()).as("只读列的库内值不得被覆盖").isEqualTo("orig-locked");
    }
}
