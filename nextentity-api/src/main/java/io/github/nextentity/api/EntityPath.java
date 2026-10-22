package io.github.nextentity.api;

/// 关联属性路径：可以继续取下一层属性，从而表达 `customer.get(Customer::getOrg)` 这类关联。
///
/// 嵌套路径的条件按 LEFT JOIN 语义求值，调用方可以依赖这两条：关联为空的行在 `isNull` 下命中，
/// 在 `eq`/`in` 下不命中；分页的 `total` 仍是满足条件的**实体行数**，不会因 join 而放大。
///
/// @param <T> 根实体类型
/// @param <U> 当前属性值类型
public interface EntityPath<T, U> extends Path<T, U> {

    /// 由方法引用得到关联属性路径。
    ///
    /// @param ref 关联属性的方法引用，非 `null`
    /// @param <T> 根实体类型
    /// @param <U> 关联实体类型
    /// @return 链长为 1 的关联路径
    static <T, U> EntityPath<T, U> of(PathRef<T, U> ref) {
        return new PathValues.EntityRoot<>(ref);
    }

    /// 取下一层属性。
    ///
    /// @param ref 下一层属性的方法引用，非 `null`；追加到路径链尾部
    /// @param <R> 下一层属性值类型
    /// @return 链长 +1 的关联路径
    <R> EntityPath<T, R> get(PathRef<U, R> ref);

    /// 取下一层字符串属性。
    ///
    /// @param ref 下一层字符串属性的方法引用，非 `null`；追加到路径链尾部
    /// @return 链长 +1 的字符串路径
    StringPath<T> get(PathRef.StringRef<U> ref);
}
