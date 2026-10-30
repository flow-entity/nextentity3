package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.DeleteWhereStep;
import io.github.nextentity.api.UpdateSetStep;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.CriteriaWriteConsistencyTest;
import io.github.nextentity.tests.jpa.support.MySqlItemSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 条件 update/delete 在真实 MySQL 上的一致性：受影响行数是 MySQL 的口径（匹配行数，不含未变更行），
/// 与 H2 侧的期望值必须相同。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemCriteriaWriteIntegrationTest extends CriteriaWriteConsistencyTest<ItemEntity> {

    private final MySqlItemSupport support = new MySqlItemSupport();

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

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
