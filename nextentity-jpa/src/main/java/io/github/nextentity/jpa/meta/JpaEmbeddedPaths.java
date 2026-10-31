package io.github.nextentity.jpa.meta;

import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.Attribute.PersistentAttributeType;
import jakarta.persistence.metamodel.Metamodel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// `select(Dto.class)` 的嵌平列：把 DTO 的扁平属性名回找成「`@Embedded` 属性 … 叶子名」路径。
///
/// 没有注解面显式指路（`@EntityPath("address.street")` 那种写法），回找同名叶子是唯一可表达的形态：
/// `PersonAddressBasic.street` ↔ `PersonWithAddress.address.street`。
///
/// 只服务 DTO 投影列（`JpaPaths.projectionPath`），且只在实体上没有同名直接属性时调用——直接属性
/// 永远优先；条件 / 排序 / 分组 / 聚合 / 条件更新是调用方显式写的路径，不经这里。
///
/// 同名叶子出现在多个嵌入组件里时语义不唯一（同一个 `@Embeddable` 在实体上出现两次就会造出这种
/// 形态），显式拒绝而不是静默挑一个：挑错的那次返回别的列，结果看着像数据错。
public final class JpaEmbeddedPaths {

    private final Metamodel metamodel;

    JpaEmbeddedPaths(Metamodel metamodel) {
        this.metamodel = metamodel;
    }

    /// 在该 managed type 的 `@Embedded` 组件（可多级）里找同名叶子。
    ///
    /// @param managedType 当前所在 managed type，非 `null`
    /// @param attribute   要解析的属性名
    /// @return 路径段（嵌入属性名 … 叶子名）；嵌入组件里没有同名叶子时返回空清单
    /// @throws IllegalArgumentException 多个嵌入组件里出现同名叶子
    public List<String> resolve(Class<?> managedType, String attribute) {
        List<List<String>> found = new ArrayList<>();
        collect(managedType, attribute, List.of(), found, new HashSet<>());
        if (found.size() > 1) {
            throw new IllegalArgumentException("属性名 " + attribute + " 在 " + managedType.getName()
                    + " 的多个 @Embedded 组件里同名，无法唯一确定 DTO 列，改用 project(ProjectionMapper) 显式给路径："
                    + found);
        }
        return found.isEmpty() ? List.of() : found.get(0);
    }

    /// 递归收集「嵌入属性名 … 叶子名」路径。
    ///
    /// `visited` 防可嵌入类型自引用造成的死递归；每层返回时移除，所以同一可嵌入类型在实体上出现
    /// 两次仍会被收集成两个候选，进而触发歧义拒绝。
    private void collect(
            Class<?> managedType,
            String attribute,
            List<String> prefix,
            List<List<String>> found,
            Set<Class<?>> visited) {
        if (!visited.add(managedType)) {
            return;
        }
        for (Attribute<?, ?> embedded : metamodel.managedType(managedType).getAttributes()) {
            if (embedded.getPersistentAttributeType() != PersistentAttributeType.EMBEDDED) {
                continue;
            }
            Class<?> embeddedType = embedded.getJavaType();
            List<String> path = new ArrayList<>(prefix);
            path.add(embedded.getName());
            if (has(embeddedType, attribute)) {
                List<String> hit = new ArrayList<>(path);
                hit.add(attribute);
                found.add(List.copyOf(hit));
            }
            collect(embeddedType, attribute, path, found, visited);
        }
        visited.remove(managedType);
    }

    /// 只把**基础属性**（`BASIC`）当候选：嵌入组件里同名的那段也可能是更深的 `@Embedded`
    /// （`Warehouse` 的 `datum` 就是），或者是单值关联（`ContactInfo` 的 `country`——它渲染成外键，
    /// 不是可投影的一列），两者都拼不出能取值的一列。
    private boolean has(Class<?> managedType, String attribute) {
        for (Attribute<?, ?> each : metamodel.managedType(managedType).getAttributes()) {
            if (each.getName().equals(attribute)
                    && each.getPersistentAttributeType() == PersistentAttributeType.BASIC) {
                return true;
            }
        }
        return false;
    }
}
