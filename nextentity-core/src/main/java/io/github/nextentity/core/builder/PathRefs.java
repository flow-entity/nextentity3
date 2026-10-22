package io.github.nextentity.core.builder;

import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.spec.AttributePath;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/// 把方法引用解析成属性路径。
///
/// 2.2.3 的做法是反射调用 lambda 的 `writeReplace()` 拿到 `SerializedLambda`
/// （`core/PathReference.java:55-74`），属性名由**方法名**去 `get`/`is` 前缀得到，
/// 不读字段、不读注解。3.x 沿用同一机制：point-mall 的 762 处 `.where(...)` 全是方法引用，
/// 换成别的方式就要改消费方代码。
public final class PathRefs {

    private PathRefs() {}

    /// 解析方法引用对应的属性路径。
    ///
    /// @param ref 方法引用（必须是实例方法引用）
    /// @param <T> 声明该属性的实体类型
    /// @return 单段属性路径
    public static <T> AttributePath resolve(PathRef<T, ?> ref) {
        SerializedLambda lambda = serializedLambda(ref);
        int kind = lambda.getImplMethodKind();
        if (kind != MethodHandleInfo.REF_invokeVirtual && kind != MethodHandleInfo.REF_invokeInterface) {
            throw new IllegalArgumentException(
                    "只接受实例方法引用（如 Order::getStatus），实际是 " + MethodHandleInfo.referenceKindToString(kind));
        }
        return AttributePath.of(declaringClass(lambda), fieldName(lambda.getImplMethodName()));
    }

    /// 在已有路径后追加一段属性名（`customer` + `orgId` → `customer.orgId`）。
    ///
    /// @param parent 父路径
    /// @param child  子属性路径（只取其属性名）
    /// @return 拼接后的路径
    public static AttributePath append(AttributePath parent, AttributePath child) {
        List<String> names = new ArrayList<>(parent.names());
        names.addAll(child.names());
        return new AttributePath(parent.entityType(), names);
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
            return trimmed.toLowerCase(java.util.Locale.ROOT);
        }
        if (Character.isUpperCase(trimmed.charAt(1))) {
            return trimmed;
        }
        return Character.toLowerCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    private static SerializedLambda serializedLambda(PathRef<?, ?> ref) {
        try {
            Method writeReplace = ref.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            return (SerializedLambda) writeReplace.invoke(ref);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "无法从方法引用解析属性路径: " + ref.getClass().getName(), e);
        }
    }

    private static Class<?> declaringClass(SerializedLambda lambda) {
        MethodType methodType = MethodType.fromMethodDescriptorString(
                lambda.getInstantiatedMethodType(), Thread.currentThread().getContextClassLoader());
        return methodType.parameterType(0);
    }
}
