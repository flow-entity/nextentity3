package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.DeleteWhereStep;
import io.github.nextentity.api.UpdateSetStep;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.CriteriaWriteConsistencyTest;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;

/// 把条件 update/delete 的一致性用例接到 JPA 后端上。
///
/// JPA 的批量写必须在事务里执行：装配 step 与执行分开，执行时包一层事务（JDBC 侧没有这个约束）。
class ItemCriteriaWriteConsistencyTest extends CriteriaWriteConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_criteria_write");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @Override
    protected int runUpdate(Consumer<UpdateSetStep<ItemEntity>> work) {
        UpdateSetStep<ItemEntity> step = support.operations().update();
        work.accept(step);
        return support.inTransaction(step::execute);
    }

    @Override
    protected int runDelete(Consumer<DeleteWhereStep<ItemEntity>> work) {
        DeleteWhereStep<ItemEntity> step = support.operations().delete();
        work.accept(step);
        return support.inTransaction(step::execute);
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
