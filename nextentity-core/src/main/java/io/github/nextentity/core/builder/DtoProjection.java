package io.github.nextentity.core.builder;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.RowMapper;
import io.github.nextentity.core.util.ImmutableList;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/// `select(Dto.class)` 的投影：DTO 属性名 → 同名实体属性列，行按 record 构造器 / setter 装回 DTO。
///
/// 只做**按名**映射：属性名就是列名契约（单段 [AttributePath]）。直接同名属性够不着时，**JPA 后端**会再到
/// 实体的 `@Embedded` 组件里找同名叶子（嵌平投影：DTO 的 `street` ↔ 实体的 `address.street`）——嵌入信息在
/// [io.github.nextentity.core.meta.EntityType] 上拿不到（core 不依赖 jakarta），所以那是后端的职责，
/// 这里只保证路径是「DTO 属性名」；JDBC 后端没有这条回找，按未知属性拒绝。关联嵌套、改名、计算列都不在
/// 这里——那些是 `project(ProjectionMapper)` 的职责，`select(Class)` 本身只声明「DTO 字段与实体属性
/// （含 `@Embedded` 叶子）」这一种最常见形态，所以它是显式 opt-in 的，不改变任何既有投影的行为。
///
/// 属性名无法与实体属性做编译期校验（[io.github.nextentity.core.meta.EntityType] 只有 id/version，
/// 没有属性清单），写错会在后端渲染时失败，不在这里静默跳过。
///
/// 包内可见：唯一调用点是同包的 `QueryBuilder#select(Class)`。
///
/// @param items  投影列（每个 DTO 属性一列）
/// @param mapper 一行结果怎么装配成 DTO
record DtoProjection(List<Projection> items, RowMapper<?> mapper) {

    /// 装配失败与无可映射属性这两条消息在 record / bean 两种形态里都要用，抽成常量避免字面量重复。
    private static final String ASSEMBLY_FAILED = "DTO 投影装配失败：";
    private static final String NO_PROPERTIES = "DTO 没有可映射的属性：";

    /// 紧凑构造器：列集复制为不可变列表。
    DtoProjection {
        items = ImmutableList.copyOf(items);
    }

    /// 由 DTO 类型与实体类型造投影。
    ///
    /// @param type       DTO 类型，非 `null`
    /// @param entityType 实体类型，非 `null`
    /// @return 投影列与装配器
    /// @throws IllegalArgumentException DTO 没有可映射的属性，普通类缺无参构造器、有同名 setter 重载，或 DTO 是接口/抽象类
    static DtoProjection of(Class<?> type, Class<?> entityType) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(entityType, "entityType");
        // 接口投影要靠运行期代理（本轮不提供）：明确拒绝，而不是让后面的反射报个说不清的错误
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            throw new IllegalArgumentException(
                    "不支持接口/抽象类的 DTO 投影：" + type.getName() + "（接口投影需要运行期代理，改用 project(ProjectionMapper) 手写映射器）");
        }
        return type.isRecord() ? record(type, entityType) : bean(type, entityType);
    }

    /// record DTO：属性名取规范构造器参数名，列序即参数序。
    private static DtoProjection record(Class<?> type, Class<?> entityType) {
        RecordComponent[] components = type.getRecordComponents();
        if (components.length == 0) {
            throw new IllegalArgumentException(NO_PROPERTIES + type.getName());
        }
        Class<?>[] parameterTypes = new Class<?>[components.length];
        List<Projection> items = new ArrayList<>(components.length);
        for (int i = 0; i < components.length; i++) {
            parameterTypes[i] = components[i].getType();
            items.add(column(entityType, components[i].getName()));
        }
        Constructor<?> constructor = constructor(type, parameterTypes);
        return new DtoProjection(items, row -> {
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = row.get(i);
            }
            return instantiate(constructor, arguments, type);
        });
    }

    /// 普通类 DTO：属性名由 `setXxx` 给，需要无参构造器，列序按属性名排序（反射方法序不保证稳定）。
    ///
    /// 合成方法（`isSynthetic()`——javac 为泛型接口、协变返回生成的桥方法必然同时是合成的）不是
    /// 独立属性，跳过——否则「同一属性两个方法」里选中哪个由 `Class#getMethods()` 的未定义顺序决定。
    /// 去掉合成方法之后仍有同名 setter 时，光看名字判断不出该装进哪一列，当场拒绝，不猜。
    private static DtoProjection bean(Class<?> type, Class<?> entityType) {
        Constructor<?> constructor = constructor(type);
        Map<String, Method> setters = new TreeMap<>();
        for (Method method : type.getMethods()) {
            if (isSetter(method)) {
                register(setters, type, method);
            }
        }
        if (setters.isEmpty()) {
            throw new IllegalArgumentException(NO_PROPERTIES + type.getName() + "（需要至少一个 setXxx，或用 record 声明）");
        }
        List<Projection> items = new ArrayList<>(setters.size());
        for (String property : setters.keySet()) {
            items.add(column(entityType, property));
        }
        List<Method> methods = List.copyOf(setters.values());
        return new DtoProjection(items, row -> {
            Object instance = instantiate(constructor, new Object[0], type);
            for (int i = 0; i < methods.size(); i++) {
                set(methods.get(i), instance, row.get(i), type);
            }
            return instance;
        });
    }

    /// 一个方法是不是 DTO 属性 setter：`setXxx` 单参数实例方法，且不是编译器生成的合成方法。
    private static boolean isSetter(Method method) {
        String name = method.getName();
        return name.length() > 3
                && name.startsWith("set")
                && method.getParameterCount() == 1
                && !Modifier.isStatic(method.getModifiers())
                && !method.isSynthetic();
    }

    /// 登记属性 → setter：同名 setter 不止一个时无法确定该装进哪一列，当场拒绝，不猜。
    private static void register(Map<String, Method> setters, Class<?> type, Method method) {
        method.setAccessible(true); // DTO 可以是包私有类，公开方法在其上也调不动
        String property = propertyName(method.getName());
        Method existing = setters.putIfAbsent(property, method);
        if (existing != null) {
            throw new IllegalArgumentException("DTO 的 setter 重载无法确定映射目标: " + type.getName() + "."
                    + property + "（多个 setter: " + setter(existing) + "、" + setter(method)
                    + "）；改用 record 或 project(ProjectionMapper)");
        }
    }

    /// DTO 列：列名是 DTO 属性名，允许后端（JPA）在实体上没有同名直接属性时回找 `@Embedded` 同名叶子。
    /// 显式投影（`select(Path)` / `project(mapper)`）不走这里，因此不参与回找。
    private static Projection column(Class<?> entityType, String property) {
        return new PathProjection(AttributePath.of(entityType, property), true);
    }

    private static Constructor<?> constructor(Class<?> type, Class<?>... parameterTypes) {
        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor(parameterTypes);
        } catch (NoSuchMethodException cause) {
            throw new IllegalArgumentException("DTO 需要无参构造器：" + type.getName() + "（或用 record 声明）", cause);
        }
        constructor.setAccessible(true);
        return constructor;
    }

    /// JavaBeans 口径的属性名：`setName` → `name`，但 `setURL` 保持 `URL`。
    private static String propertyName(String setter) {
        String rest = setter.substring(3);
        if (rest.length() > 1 && Character.isUpperCase(rest.charAt(0)) && Character.isUpperCase(rest.charAt(1))) {
            return rest;
        }
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }

    /// 重载冲突消息里的 setter 形状：`setValue(String)` / `setValue(int)`。
    private static String setter(Method method) {
        return method.getName() + "(" + method.getParameterTypes()[0].getSimpleName() + ")";
    }

    private static Object instantiate(Constructor<?> constructor, Object[] arguments, Class<?> type) {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException cause) {
            // DTO 构造器自己抛了：把原始原因带出来，否则只剩一句「构造失败」
            throw new IllegalStateException(ASSEMBLY_FAILED + type.getName() + " 的构造器抛出异常", cause.getCause());
        } catch (ReflectiveOperationException | IllegalArgumentException cause) {
            throw new IllegalStateException(ASSEMBLY_FAILED + type.getName() + "（列值与构造器参数类型不匹配？）", cause);
        }
    }

    private static void set(Method setter, Object instance, Object value, Class<?> type) {
        try {
            setter.invoke(instance, value);
        } catch (InvocationTargetException cause) {
            throw new IllegalStateException(
                    ASSEMBLY_FAILED + type.getName() + " 的 " + setter.getName() + " 抛出异常", cause.getCause());
        } catch (ReflectiveOperationException | IllegalArgumentException cause) {
            throw new IllegalStateException(
                    ASSEMBLY_FAILED + type.getName() + " 的 " + setter.getName() + "（列值与 setter 参数类型不匹配？）", cause);
        }
    }
}
