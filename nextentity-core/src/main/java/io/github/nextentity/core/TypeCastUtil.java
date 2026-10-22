package io.github.nextentity.core;

/// 受检转型工具：把"编译器无法证明、但调用方确知"的类型转换集中到一处。
///
/// 只保留 `cast(Class)` —— 唯一调用点是 point-mall 的 `CsvHandler.getRequestClass()`
/// （`ResolvableType.resolveGeneric(0)` 返回 `Class<?>`）。其它转型重载没有调用点，不建。
public final class TypeCastUtil {

    private TypeCastUtil() {}

    /// 把 `Class<?>` 当成 `Class<T>` 返回。
    ///
    /// @param resolve 原始类型
    /// @param <T>     目标类型参数
    /// @return 同一个 Class 对象
    public static <T> Class<T> cast(Class<?> resolve) {
        @SuppressWarnings("unchecked")
        Class<T> casted = (Class<T>) resolve;
        return casted;
    }
}
