package io.github.nextentity.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.repo.EntityOperations;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// {@link AbstractRepository} 的**委托形状**证据：泛型解析出哪个实体、两处转发有没有换过对象。
///
/// 这里钉的只是「委托形状」，**不是**业务正确性：假工厂与假 `EntityOperations` 只记录入参、
/// 不落库、不渲染 SQL，按 AGENTS「mock 只许证形状」，本用例不得被当作任何查询/写入能力的
/// 正确性证据（正确性证据在 `nextentity-tests-jpa` 的真库对拍里）。
///
/// 本类证明三件事：
/// - 构造器把子类泛型实参解析成 `Item.class` 交给工厂（钉住
///   `ResolvableType.forClass(getClass()).as(AbstractRepository.class).resolveGeneric(0)`）；
/// - `query()` 返回的就是 `EntityOperations.query()` 给出的那个实例，且每次调用都转发（不缓存、不自建）；
/// - `insert(entity)` 把同一个 entity 原样转给 `EntityOperations.insert(...)`。
///
/// 不需要 Spring 上下文：`ResolvableType` 只看类形状，`AbstractRepository` 也不读上下文。
class AbstractRepositoryTest {

    /// 实体类：`ResolvableType` 只需要类形状，不需要 JPA 映射，因此不引入容器。
    private record Item(long id) {}

    /// 具体子类：泛型实参即「工厂应当收到的实体类型」。
    private static final class ItemRepository extends AbstractRepository<Item> {

        private ItemRepository(EntityOperationsFactory factory) {
            super(factory);
        }
    }

    /// 自身仍是泛型的子类：`AbstractRepository` 的实参是它自己的类型变量，解析不出具体实体类。
    private static final class GenericRepository<E> extends AbstractRepository<E> {

        private GenericRepository(EntityOperationsFactory factory) {
            super(factory);
        }
    }

    /// 构造器把泛型实参解析成 `Item.class` 交给工厂。
    ///
    /// `Class` 相等是引用相等：`containsExactly(Item.class)` 用 `equals`，`Class` 的 `equals` 即同一性。
    @Test
    void constructorResolvesEntityTypeFromGenericArgument() {
        RecordingFactory factory = new RecordingFactory();

        new ItemRepository(factory);

        assertThat(factory.requestedTypes).containsExactly(Item.class);
    }

    /// `query()` 原样返回 `EntityOperations.query()` 的实例，且每次调用都转发。
    ///
    /// 两次调用后计数为 2 且两次都是同一个实例：证明「委托」而不是「缓存」或「自己 new 一个」。
    @Test
    void queryReturnsTheInstanceProvidedByOperations() {
        RecordingFactory factory = new RecordingFactory();
        ItemRepository repository = new ItemRepository(factory);

        EntityQuery<Item> first = repository.query();
        EntityQuery<Item> second = repository.query();

        assertThat(first).isSameAs(factory.operations.queryInstance);
        assertThat(second).isSameAs(factory.operations.queryInstance);
        assertThat(factory.operations.queryCalls).isEqualTo(2);
    }

    /// `insert(entity)` 把同一个 entity 原样交给 `EntityOperations.insert(...)`。
    @Test
    void insertPassesTheSameEntityToOperations() {
        RecordingFactory factory = new RecordingFactory();
        ItemRepository repository = new ItemRepository(factory);
        Item entity = new Item(42L);

        repository.insert(entity);

        assertThat(factory.operations.inserted).hasSize(1);
        assertThat(factory.operations.inserted.getFirst()).isSameAs(entity);
    }

    /// 泛型实参解析不出来时当场拒绝，并在消息里点名仓库类。
    ///
    /// 解析结果是 `null` 时若继续往下传，空指针会落在 `JpaMeta` 的 `ConcurrentHashMap` 里
    /// （`ConcurrentHashMap` 不接受 `null` 键），栈里看不到是哪个仓库写错了泛型。
    /// 这里用「子类自身仍是泛型」制造解析不出来的情形：容器把这类类当 raw bean 注册时就是这个形状。
    @Test
    void unresolvableEntityTypeIsRejectedWithRepositoryName() {
        RecordingFactory factory = new RecordingFactory();

        assertThatThrownBy(() -> new GenericRepository<Item>(factory))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(GenericRepository.class.getName())
                .hasMessageContaining("泛型实参");
        assertThat(factory.requestedTypes).as("拒绝发生在取操作入口之前").isEmpty();
    }

    /// 记录「被请求的实体类型」的假工厂：只服务一个实体，因此始终返回同一个假操作入口。
    private static final class RecordingFactory implements EntityOperationsFactory {

        private final List<Class<?>> requestedTypes = new ArrayList<>();

        private final RecordingOperations operations = new RecordingOperations();

        @Override
        public <T> EntityOperations<T> operations(Class<T> entityClass) {
            requestedTypes.add(entityClass);
            @SuppressWarnings("unchecked")
            EntityOperations<T> shared = (EntityOperations<T>) operations;
            return shared;
        }
    }

    /// 记录 `query()` / `insert(...)` 调用次数与入参的假操作入口。
    ///
    /// 其余成员一律抛 {@link UnsupportedOperationException}：`AbstractRepository` 一旦多碰一个方法，
    /// 用例就当场失败，这样「只用到这两个成员」本身也是被钉住的。
    private static final class RecordingOperations implements EntityOperations<Item> {

        private final EntityQuery<Item> queryInstance = queryStub();

        private int queryCalls;

        private final List<Item> inserted = new ArrayList<>();

        @Override
        public EntityQuery<Item> query() {
            queryCalls++;
            return queryInstance;
        }

        @Override
        public void insert(Item entity) {
            inserted.add(entity);
        }

        @Override
        public void insertAll(Iterable<Item> entities) {
            throw new UnsupportedOperationException("AbstractRepository 不批量插入");
        }

        @Override
        public void update(Item entity) {
            throw new UnsupportedOperationException("AbstractRepository 不更新");
        }

        @Override
        public void updateAll(Iterable<Item> entities) {
            throw new UnsupportedOperationException("AbstractRepository 不批量更新");
        }

        @Override
        public EntityType<Item> entityType() {
            throw new UnsupportedOperationException("AbstractRepository 不读元数据");
        }
    }

    /// 查询入口空壳：本用例只断言「返回的就是这个实例」，不触碰查询对象本身。
    ///
    /// 任何方法调用都会抛错，因此它顺带证明委托路径没有额外调用查询入口上的方法。
    /// 用 JDK 动态代理而不是引入 mock 框架：项目测试栈只有 JUnit 5 + AssertJ。
    private static EntityQuery<Item> queryStub() {
        InvocationHandler failOnUse = (proxy, method, args) -> {
            throw new UnsupportedOperationException("本用例不调用查询入口的方法：" + method.getName());
        };
        @SuppressWarnings("unchecked")
        EntityQuery<Item> stub = (EntityQuery<Item>) Proxy.newProxyInstance(
                EntityQuery.class.getClassLoader(), new Class<?>[] {EntityQuery.class}, failOnUse);
        return stub;
    }
}
