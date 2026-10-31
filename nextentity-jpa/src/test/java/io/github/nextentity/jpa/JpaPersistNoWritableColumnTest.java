package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jpa.entity.AllReadOnlyFlags;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 实体写路径（`JpaPersistExecutor.setValues`）在**一个可写列都没有**时的契约：显式拒绝，
/// 而不是把空的 `SET` 交给 provider。
///
/// 只读列跳过是 3.1.0 的能力（`@Column(updatable=false)` 不进 `UPDATE`），但当所有列都只读时
/// `SET` 会变空；Hibernate 对空赋值抛的是 `IllegalArgumentException("No assignments specified as
/// part of UPDATE criteria")`——provider 内部异常，不是本库的拒绝契约。JDBC 后端在
/// `WriteSql.update` 里已显式抛 `UnsupportedOperationException`（3.0.0：无 @Version 且
/// 无可写标量列的 update 明确拒绝（UOE），不静默丢语义」），JPA 侧对齐同一文案。
class JpaPersistNoWritableColumnTest extends JpaTestSupport {

    @BeforeEach
    void seedAllReadOnlyFlags() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from AllReadOnlyFlags").executeUpdate();
            entityManager.persist(new AllReadOnlyFlags(1L, "orig", "orig-memo"));
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("实体 update：除主键外没有可写列时显式 UOE，不下发空 SET 的 update")
    void updateWithoutWritableColumnsIsRejected() {
        inTx(entityManager -> {
            JpaPersistExecutor<AllReadOnlyFlags> executor =
                    new JpaPersistExecutor<>(entityManager, meta(), AllReadOnlyFlags.class);
            assertThatThrownBy(() -> executor.update(new AllReadOnlyFlags(1L, "hacked", "hacked-memo")))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("实体 " + AllReadOnlyFlags.class.getName()
                            + " 没有可写标量列，update 无法生成 SET 子句：可写标量列都带"
                            + " @Column(updatable=false)，也没有 @Version 列可以自增；请至少保留一个可更新列");
            return null;
        });
        assertThat(normalized(drainSql())).as("拒绝时不得下发任何 UPDATE").isEmpty();

        AllReadOnlyFlags read = inTx(entityManager -> entityManager.find(AllReadOnlyFlags.class, 1L));
        assertThat(read.getState()).as("只读列的库内值不得被覆盖").isEqualTo("orig");
        assertThat(read.getMemo()).as("只读列的库内值不得被覆盖").isEqualTo("orig-memo");
    }
}
