package io.github.nextentity.core.builder;

import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.spec.AttributePath;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// 把方法引用解析成属性路径。
///
/// 2.2.3 的做法是反射调用 lambda 的 `writeReplace()` 拿到 `SerializedLambda`
/// （`core/PathReference.java:55-74`），属性名由**方法名**去 `get`/`is` 前缀得到，
/// 不读字段、不读注解。3.x 沿用同一机制：point-mall 的 762 处 `.where(...)` 全是方法引用，
/// 换成别的方式就要改消费方代码。
///
/// 入参恒为**用户写下的方法引用**：api 的 `Path` 不再继承 `PathRef`，多段路径由调用方
/// 先用 `Path#refs()` 取到原始 lambda 链再交进来，因此这里不需要也不允许"兼容值对象"。
final class PathRefs {

    private PathRefs() {}

    /// 解析单个方法引用（单段路径）。
    ///
    /// @param ref 方法引用
    /// @return 单段属性路径
    static AttributePath resolve(PathRef<?, ?> ref) {
        return resolve(List.of(ref));
    }

    /// 解析一条属性路径链（`api.Path.refs()` / `api.Op.refs()`，根→叶）。
    ///
    /// 路径起点的实体类型取第一段方法引用的声明类型，`customer.get(org).get(name)`
    /// 因此得到 `AttributePath(Order, ["customer","org","name"])`。
    ///
    /// @param refs 从根属性到叶子属性的方法引用链，非空
    /// @return 属性路径
    static AttributePath resolve(List<? extends PathRef<?, ?>> refs) {
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("属性路径链不能为空");
        }
        Class<?> entityType = declaringClass(serializedLambda(refs.getFirst()));
        List<String> names = new ArrayList<>(refs.size());
        for (PathRef<?, ?> ref : refs) {
            names.add(fieldName(serializedLambda(ref).getImplMethodName()));
        }
        return new AttributePath(entityType, names);
    }

    /// 方法名 → 属性名：`getStatus` → `status`、`isActive` → `active`、`status` → `status`。
    ///
    /// 与 2.2.3 `PathReference.getFieldName` 逐字符对齐（含"第二个字符大写则不改首字母"这条）。
    ///
    /// @param methodName 方法名
    /// @return 属性名
    static String fieldName(String methodName) {
        String trimmed;
        if (methodName.length() > 3 && methodName.startsWith("get")) {
            trimmed = methodName.substring(3);
        } else if (methodName.length() > 2 && methodName.startsWith("is")) {
            trimmed = methodName.substring(2);
        } else {
            return methodName;
        }
        if (trimmed.length() == 1) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if (Character.isUpperCase(trimmed.charAt(1))) {
            return trimmed;
        }
        return Character.toLowerCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    private static SerializedLambda serializedLambda(PathRef<?, ?> ref) {
        Method writeReplace;
        try {
            writeReplace = ref.getClass().getDeclaredMethod("writeReplace");
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "属性路径的某一段不是方法引用（class=" + ref.getClass().getName()
                            + "）：属性路径必须用方法引用表达，例如 Order::getStatus 或 EntityPath.of(A::getB).get(C::getD)",
                    e);
        }
        try {
            writeReplace.setAccessible(true);
            return (SerializedLambda) writeReplace.invoke(ref);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "无法从方法引用解析属性路径: " + ref.getClass().getName(), e);
        }
    }

    private static Class<?> declaringClass(SerializedLambda lambda) {
        int kind = lambda.getImplMethodKind();
        if (kind != MethodHandleInfo.REF_invokeVirtual && kind != MethodHandleInfo.REF_invokeInterface) {
            throw new IllegalArgumentException(
                    "只接受实例方法引用（如 Order::getStatus），实际是 " + MethodHandleInfo.referenceKindToString(kind));
        }
        MethodType methodType = MethodType.fromMethodDescriptorString(
                lambda.getInstantiatedMethodType(), Thread.currentThread().getContextClassLoader());
        return methodType.parameterType(0);
    }
}
