package io.github.nextentity.integration.config;

import io.github.nextentity.integration.config.env.DatabaseEnvironmentVariables;
import io.github.nextentity.integration.config.env.Env;
import io.github.nextentity.integration.entity.Address;
import io.github.nextentity.integration.entity.AddressWithZip;
import io.github.nextentity.integration.entity.AutoIncrementEntity;
import io.github.nextentity.integration.entity.Category;
import io.github.nextentity.integration.entity.ContactInfo;
import io.github.nextentity.integration.entity.Customer;
import io.github.nextentity.integration.entity.Department;
import io.github.nextentity.integration.entity.Employee;
import io.github.nextentity.integration.entity.InstantEntity;
import io.github.nextentity.integration.entity.LockableEntity;
import io.github.nextentity.integration.entity.PersonWithAddress;
import io.github.nextentity.integration.entity.PersonWithCrossLayerEmbedded;
import io.github.nextentity.integration.entity.PersonWithNestedAddress;
import io.github.nextentity.integration.entity.PersonWithNestedOverriddenContact;
import io.github.nextentity.integration.entity.PersonWithOverriddenAddress;
import io.github.nextentity.integration.entity.SalesOrder;
import io.github.nextentity.integration.entity.ZipCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

/// 参数化用例的环境来源：每个启用的数据库一个 `IntegrationTestContext`。
///
/// 与 2.2.3 的差别：2.2.3 靠 Spring 容器的 `@AfterEach reset()` 收尾，漏写就污染后面的用例；
/// 这里在**交付上下文之前**先复位，隔离不再依赖用例作者记得写 `@AfterEach`。
public class IntegrationTestProvider implements ArgumentsProvider {

    private static final Class<?>[] ENTITY_CLASSES = {
        Address.class,
        AddressWithZip.class,
        AutoIncrementEntity.class,
        Category.class,
        ContactInfo.class,
        Customer.class,
        Department.class,
        Employee.class,
        InstantEntity.class,
        LockableEntity.class,
        PersonWithAddress.class,
        PersonWithCrossLayerEmbedded.class,
        PersonWithNestedAddress.class,
        PersonWithNestedOverriddenContact.class,
        PersonWithOverriddenAddress.class,
        SalesOrder.class,
        ZipCode.class
    };

    private static final Map<String, IntegrationTestContext> CONTEXTS = new LinkedHashMap<>();

    private static final ThreadLocal<IntegrationTestContext> CURRENT = new ThreadLocal<>();

    /// 启动全部启用的环境（整个 JVM 一次）。
    private static synchronized Map<String, IntegrationTestContext> contexts() {
        if (CONTEXTS.isEmpty()) {
            for (DatabaseEnvironmentVariables environment : Env.dbs()) {
                CONTEXTS.put(environment.name(), open(environment));
            }
        }
        return CONTEXTS;
    }

    private static IntegrationTestContext open(DatabaseEnvironmentVariables environment) {
        LegacyHarness harness = LegacyHarness.start(environment, ENTITY_CLASSES);
        return new HarnessIntegrationTestContext(harness);
    }

    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
        return contexts().values().stream().peek(IntegrationTestContext::reset).map(Arguments::of);
    }

    /// 当前用例的上下文（少数用例要拿它做跨方法断言）。
    public static IntegrationTestContext getEntityManagerContext() {
        return CURRENT.get();
    }

    /// 关闭全部环境（JVM 退出前由 surefire 触发的收尾用例调用）。
    public static synchronized void closeAll() {
        for (IntegrationTestContext context : CONTEXTS.values()) {
            ((HarnessIntegrationTestContext) context).close();
        }
        CONTEXTS.clear();
    }

    /// 环境的用例上下文实现。
    static final class HarnessIntegrationTestContext implements IntegrationTestContext {

        private final LegacyHarness harness;

        HarnessIntegrationTestContext(LegacyHarness harness) {
            this.harness = harness;
        }

        @Override
        public <T> io.github.nextentity.core.repo.EntityOperations<T> operations(Class<T> type) {
            return harness.operations(type);
        }

        @Override
        public void delete(Object entity) {
            harness.delete(entity);
        }

        @Override
        public void deleteAll(Iterable<?> entities) {
            harness.deleteAll(entities);
        }

        @Override
        public IntegrationTestContext reset() {
            harness.reset();
            CURRENT.set(this);
            return this;
        }

        @Override
        public <T> T doInTransaction(java.util.function.Supplier<T> runnable) {
            return harness.inTransaction(runnable);
        }

        void close() {
            harness.close();
        }

        @Override
        public String toString() {
            return harness.environmentName();
        }
    }

    /// 供用例直接取全部环境名（显示名 / 断言用）。
    public static List<String> environmentNames() {
        return List.copyOf(contexts().keySet());
    }
}
