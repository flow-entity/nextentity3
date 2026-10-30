package io.github.nextentity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `FetchStep` 的默认重载：一个关联一次一参、多个关联一次多参，全部收拢到
/// `fetch(Collection<? extends Path<T,?>>)` 这一个抽象方法上。
///
/// 断言的是**收集到的路径链**——默认重载唯一能承诺的就是"把哪几条路径交给实现"；
/// 路径怎么渲染成 join 由后端负责（api 不持有方言）。
///
/// 用动态代理而不是写一个完整实现类：`FetchStep` 继承了一整套步骤接口，
/// 手写实现会把 30 多个无关方法引进用例；这里只需要"记录抽象的 fetch(Collection)"，
/// 其余方法一律调回接口默认实现（`InvocationHandler.invokeDefault`）。
@DisplayName("FetchStep 默认重载：路径收集与委托")
class FetchStepDefaultsTest {

    private final RecordingFetchStep step = new RecordingFetchStep();

    @Test
    @DisplayName("单条 Path：收集成只含该路径的集合，并返回同一条查询")
    void singlePath() {
        Path<Employee, String> name = Path.of(Employee::getName);

        BaseWhereStep<Employee, Employee> next = step.proxy.fetch(name);

        assertThat(next).isSameAs(step.proxy);
        assertThat(step.batches).containsExactly(List.of(name));
    }

    @Test
    @DisplayName("两条 / 三条 Path：一次调用传整批，而不是链两次")
    void multiplePathsInOneCall() {
        Path<Employee, String> name = Path.of(Employee::getName);
        Path<Employee, String> status = Path.of(Employee::getStatus);
        Path<Employee, Integer> age = Path.of(Employee::getAge);

        step.proxy.fetch(name, status);
        step.proxy.fetch(name, status, age);

        assertThat(step.batches).containsExactly(List.of(name, status), List.of(name, status, age));
    }

    @Test
    @DisplayName("PathRef 重载：先包成 Path（refs 就是传入的方法引用）再走 Path 重载，结果只有一批")
    void pathRefOverloadsConvertToPath() {
        PathRef<Employee, String> name = Employee::getName;
        PathRef<Employee, String> status = Employee::getStatus;

        BaseWhereStep<Employee, Employee> single = step.proxy.fetch(name);
        BaseWhereStep<Employee, Employee> pair = step.proxy.fetch(name, status);

        assertThat(single).isSameAs(step.proxy);
        assertThat(pair).isSameAs(step.proxy);
        assertThat(refsOf(step.batches)).containsExactly(List.of(name), List.of(name, status));
    }

    @Test
    @DisplayName("抽象方法：一次调用就是一批，默认重载不额外拆分")
    void collectionOverloadIsTheSingleSink() {
        Path<Employee, String> name = Path.of(Employee::getName);
        List<Path<Employee, ?>> batch = List.of(name, Path.of(Employee::getAge));

        BaseWhereStep<Employee, Employee> next = step.proxy.fetch(batch);

        assertThat(next).isSameAs(step.proxy);
        assertThat(step.batches).containsExactly(batch);
    }

    /// `PathValues.Root` 没有改写 `equals`（两个 `Path.of` 结果只能按同一方法引用对象比），
    /// 因此断言路径链时比 `refs()`：默认重载承诺的是"把传入的那条路径原样交下去"。
    private static List<List<PathRef<?, ?>>> refsOf(List<List<Path<Employee, ?>>> batches) {
        List<List<PathRef<?, ?>>> refs = new ArrayList<>();
        for (List<Path<Employee, ?>> batch : batches) {
            List<PathRef<?, ?>> batchRefs = new ArrayList<>();
            for (Path<Employee, ?> path : batch) {
                batchRefs.addAll(path.refs());
            }
            refs.add(batchRefs);
        }
        return refs;
    }

    // ---------------------------------------------------------------- fixture

    /// 代理全部 `FetchStep` 方法：抽象的 `fetch(Collection)` 记下这一批路径并返回自身（链式形状）；
    /// 其余方法（含 5 个默认 `fetch` 重载与步骤接口上的方法）调回接口默认实现。
    private static final class RecordingFetchStep implements InvocationHandler {

        private final List<List<Path<Employee, ?>>> batches = new ArrayList<>();

        private final FetchStep<Employee> proxy = newProxy();

        @SuppressWarnings("unchecked")
        private FetchStep<Employee> newProxy() {
            return (FetchStep<Employee>)
                    Proxy.newProxyInstance(FetchStep.class.getClassLoader(), new Class<?>[] {FetchStep.class}, this);
        }

        @Override
        public Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("fetch".equals(name) && args.length == 1 && args[0] instanceof Collection<?> batch) {
                List<Path<Employee, ?>> paths = new ArrayList<>();
                for (Object path : batch) {
                    paths.add((Path<Employee, ?>) path);
                }
                batches.add(paths);
                return proxy;
            }
            if ("fetch".equals(name)) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            if ("toString".equals(name)) {
                return "RecordingFetchStep";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return target == args[0];
            }
            throw new UnsupportedOperationException("未预期的方法：" + name);
        }
    }

    private record Employee(String name, String status, Integer age) {

        String getName() {
            return name;
        }

        String getStatus() {
            return status;
        }

        Integer getAge() {
            return age;
        }
    }
}
