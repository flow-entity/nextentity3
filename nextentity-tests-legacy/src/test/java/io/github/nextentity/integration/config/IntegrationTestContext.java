package io.github.nextentity.integration.config;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.integration.entity.AutoIncrementEntity;
import io.github.nextentity.integration.entity.Category;
import io.github.nextentity.integration.entity.Customer;
import io.github.nextentity.integration.entity.Department;
import io.github.nextentity.integration.entity.Employee;
import io.github.nextentity.integration.entity.LockableEntity;
import io.github.nextentity.integration.entity.PersonWithAddress;
import io.github.nextentity.integration.entity.PersonWithCrossLayerEmbedded;
import io.github.nextentity.integration.entity.PersonWithNestedAddress;
import io.github.nextentity.integration.entity.PersonWithNestedOverriddenContact;
import io.github.nextentity.integration.entity.PersonWithOverriddenAddress;
import io.github.nextentity.integration.entity.SalesOrder;
import java.util.function.Supplier;

/// 一个数据库环境上的用例上下文：查询、写入、事务、复位。
///
/// 2.2.3 的同名接口暴露的是 `EntityTemplateFactory` / `EntityQueryImpl` 这套 2.x 入口；
/// 3.x 的公开入口只剩 `EntityOperations`，所以这里直接把它暴露给用例——
/// 用例改的是"从哪拿到查询"，断言本身不动，被测语义因此没有中间层可以藏。
public interface IntegrationTestContext {

    /// 实体的操作入口（写方法已包事务，见 `LegacyHarness#operations`）。
    <T> EntityOperations<T> operations(Class<T> type);

    /// 删除一条实体（2.2.3 `PersistExecutor.delete` 的对位）。
    void delete(Object entity);

    /// 批量删除（2.2.3 `PersistExecutor.deleteAll` 的对位）。
    void deleteAll(Iterable<?> entities);

    /// 清空全部表并重装夹具。
    IntegrationTestContext reset();

    /// 在事务里执行；已有事务则加入。
    <T> T doInTransaction(Supplier<T> runnable);

    default void doInTransaction(Runnable runnable) {
        doInTransaction(() -> {
            runnable.run();
            return null;
        });
    }

    default EntityQuery<Employee> queryEmployees() {
        return operations(Employee.class).query();
    }

    default EntityQuery<Department> queryDepartments() {
        return operations(Department.class).query();
    }

    default EntityQuery<LockableEntity> queryLockableEntities() {
        return operations(LockableEntity.class).query();
    }

    default EntityQuery<AutoIncrementEntity> queryAutoIncrementEntities() {
        return operations(AutoIncrementEntity.class).query();
    }

    default EntityQuery<Category> queryCategories() {
        return operations(Category.class).query();
    }

    default EntityQuery<SalesOrder> querySalesOrders() {
        return operations(SalesOrder.class).query();
    }

    default EntityQuery<Customer> queryCustomers() {
        return operations(Customer.class).query();
    }

    default EntityQuery<PersonWithAddress> queryPersonWithAddresses() {
        return operations(PersonWithAddress.class).query();
    }

    default EntityQuery<PersonWithNestedAddress> queryPersonWithNestedAddresses() {
        return operations(PersonWithNestedAddress.class).query();
    }

    default EntityQuery<PersonWithOverriddenAddress> queryPersonWithOverriddenAddresses() {
        return operations(PersonWithOverriddenAddress.class).query();
    }

    default EntityQuery<PersonWithNestedOverriddenContact> queryPersonWithNestedOverriddenContacts() {
        return operations(PersonWithNestedOverriddenContact.class).query();
    }

    default EntityQuery<PersonWithCrossLayerEmbedded> queryPersonWithCrossLayerEmbedded() {
        return operations(PersonWithCrossLayerEmbedded.class).query();
    }
}
