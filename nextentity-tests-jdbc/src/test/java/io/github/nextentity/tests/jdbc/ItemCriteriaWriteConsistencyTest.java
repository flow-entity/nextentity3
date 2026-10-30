package io.github.nextentity.tests.jdbc;

import io.github.nextentity.api.DeleteWhereStep;
import io.github.nextentity.api.UpdateSetStep;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.CriteriaWriteConsistencyTest;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// 条件 update / delete 的 JDBC 接线：JDBC 的写入建立在自动提交之上，`execute()` 直接跑、不需要事务。
///
/// 这块此前在 JDBC 侧 0 个接线子类（`nextentity-jdbc` 只有 SQL 形状单测）。形状对不等于语义对：
/// 「affected rows」与「读回被改的值」必须来自真库，所以把它接进一致性基类。
/// 只读列（`@Column(updatable=false)`）被拒的逐字文案由 golden schema 契约
/// `GoldenSchemaLandingContract#conditionalUpdateOfNonUpdatableColumnIsRejected` 钉住
/// （Item 夹具没有只读列，无法在这条接线上覆盖；两边同为「属性全名 + 列名点分」的逐字断言）。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemCriteriaWriteConsistencyTest extends CriteriaWriteConsistencyTest<ItemEntity> {

    private final JdbcItemSupport support = new JdbcItemSupport("itemcriteriawriteconsistencytest");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @Override
    protected int runUpdate(Consumer<UpdateSetStep<ItemEntity>> work) {
        UpdateSetStep<ItemEntity> step = support.operations().update();
        work.accept(step);
        return step.execute();
    }

    @Override
    protected int runDelete(Consumer<DeleteWhereStep<ItemEntity>> work) {
        DeleteWhereStep<ItemEntity> step = support.operations().delete();
        work.accept(step);
        return step.execute();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
