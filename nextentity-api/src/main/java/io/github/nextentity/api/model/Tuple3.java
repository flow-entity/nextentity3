package io.github.nextentity.api.model;

/// 三列投影结果：具名 `get0`~`get2` + 按位置 `get(int)`。
///
/// 由 `SelectStep#select(...)` 的三列重载产出。四列及以上改用 [io.github.nextentity.api.ProjectionMappers] 产出的映射器。
/// 优先用具名的 `getN()`（有静态类型），`get(int)` 只返回 `Object`。
///
/// @param <A> 第一列类型
/// @param <B> 第二列类型
/// @param <C> 第三列类型
/// @param get0 第一列的值
/// @param get1 第二列的值
/// @param get2 第三列的值
public record Tuple3<A, B, C>(A get0, B get1, C get2) implements Tuple {

    @Override
    public int size() {
        return 3;
    }

    @Override
    public Object get(int index) {
        return switch (index) {
            case 0 -> get0;
            case 1 -> get1;
            case 2 -> get2;
            default -> throw new IndexOutOfBoundsException("三列元组没有下标 " + index);
        };
    }
}
