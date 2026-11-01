package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.ShopOrder;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 关联对象不能当投影列：显式拒绝，不静默 join 出被关联表。
///
/// 本后端的 Criteria 投影允许末段是关联属性，Hibernate 会把 `select(...)` 里的 `root.get("customer")`
/// 渲染成对被关联表的 **inner join**（`from shop_order so1_0 join customer c1_0 on ...`）。
/// 后果不是「多取一列」：外键为 `null` 的行被 join 掉（`list()` 少行，而 `count()` 不渲染投影列、
/// 不注册这个 join，数字对不上），且拿到的关联对象是另一条装配链的产物。
/// `select(Class)` 的契约只覆盖「DTO 属性名 = 实体属性名（含 `@Embedded` 叶子）」，跨表路径属于
/// `project(ProjectionMapper)`；JDBC 后端在同一形态上早已显式拒绝（`投影关联对象未支持`），
/// 这里与它同口径、同文案。
class JpaAssociationProjectionTest extends JpaTestSupport {

    record OrderWithCustomer(Long id, Customer customer) {}

    record OrderAmount(Long id, BigDecimal amount) {}

    @Test
    @DisplayName("select(Dto)：DTO 属性是关联属性时显式拒绝（不静默 inner join、不丢 FK 为 null 的行）")
    void dtoWithAssociationPropertyIsRejected() {
        assertThatThrownBy(() -> inTx(entityManager -> orders(entityManager)
                        .query()
                        .select(OrderWithCustomer.class)
                        .list()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("投影关联对象未支持")
                .hasMessageContaining(ShopOrder.class.getName() + ".customer");
    }

    @Test
    @DisplayName("select(关联属性)：值投影同样拒绝，与 JDBC 同口径")
    void associationValueProjectionIsRejected() {
        assertThatThrownBy(() -> inTx(entityManager -> orders(entityManager)
                        .query()
                        .select(Path.of(ShopOrder::getCustomer))
                        .list()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("投影关联对象未支持")
                .hasMessageContaining(ShopOrder.class.getName() + ".customer");
    }

    @Test
    @DisplayName("对照：DTO 只取本表列照常可用（拒绝只针对关联属性这一种形态）")
    void plainDtoProjectionStillWorks() {
        List<OrderAmount> rows = inTx(entityManager -> orders(entityManager)
                .query()
                .select(OrderAmount.class)
                .orderBy(ShopOrder::getId)
                .asc()
                .list());

        assertThat(rows).extracting(OrderAmount::id).containsExactly(100L, 101L);
    }

    private EntityOperations<ShopOrder> orders(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(ShopOrder.class);
    }
}
