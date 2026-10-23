package io.github.nextentity.api.model;

/// 两列投影结果：具名 `get0`/`get1` + 按位置 `get(int)`。
///
/// 由 `SelectStep#select(Expression, Expression)` 产出。与 [Tuple3]~[Tuple6] 一样是 record：
/// 值语义（按列值相等）、具名访问器、按位置取值三者齐备。
///
/// @param <A> 第一列类型
/// @param <B> 第二列类型
/// @param get0 第一列的值，即 `select(first, second)` 的 `first`
/// @param get1 第二列的值，即 `select(first, second)` 的 `second`
public record Tuple2<A, B>(A get0, B get1) implements Tuple {

    @Override
    public int size() {
        return 2;
    }

    @Override
    public Object get(int index) {
        return switch (index) {
            case 0 -> get0;
            case 1 -> get1;
            default -> throw new IndexOutOfBoundsException("两列元组没有下标 " + index);
        };
    }
}
