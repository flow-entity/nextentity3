package io.github.nextentity.api.model;

/// 五列投影结果：具名 `get0`~`get4` + 按位置 `get(int)`。
///
/// 由 [io.github.nextentity.api.ProjectionMappers] 产出的映射器给出（四列及以上不走 `select`）。
/// 优先用具名的 `getN()`（有静态类型），`get(int)` 只返回 `Object`。
///
/// @param <A> 第一列类型
/// @param <B> 第二列类型
/// @param <C> 第三列类型
/// @param <D> 第四列类型
/// @param <E> 第五列类型
/// @param get0 第一列的值
/// @param get1 第二列的值
/// @param get2 第三列的值
/// @param get3 第四列的值
/// @param get4 第五列的值
public record Tuple5<A, B, C, D, E>(A get0, B get1, C get2, D get3, E get4) implements Tuple {

    @Override
    public int size() {
        return 5;
    }

    @Override
    public Object get(int index) {
        return switch (index) {
            case 0 -> get0;
            case 1 -> get1;
            case 2 -> get2;
            case 3 -> get3;
            case 4 -> get4;
            default -> throw new IndexOutOfBoundsException("五列元组没有下标 " + index);
        };
    }
}
