package io.github.nextentity.api;

/// 关联属性路径：可以继续取下一层属性，从而表达 `customer.get(Customer::getOrg)` 这类关联。
///
/// @param <T> 根实体类型
/// @param <U> 当前属性值类型
public interface EntityPath<T, U> extends Path<T, U> {

    /// 由方法引用得到关联属性路径。
    ///
    /// @param ref 方法引用形式的关联属性
    /// @param <T> 根实体类型
    /// @param <U> 关联实体类型
    /// @return 关联属性路径
    static <T, U> EntityPath<T, U> of(PathRef<T, U> ref) {
        return new PathValues.EntityRoot<>(ref);
    }

    /// 取下一层属性。
    ///
    /// @param ref 下一层属性
    /// @param <R> 下一层属性值类型
    /// @return 下一层属性路径
    <R> EntityPath<T, R> get(PathRef<U, R> ref);

    /// 取下一层字符串属性。
    ///
    /// @param ref 下一层字符串属性
    /// @return 下一层字符串属性路径
    StringPath<T> get(PathRef.StringRef<U> ref);
}
