package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.Op;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 值层回归（不碰数据库）：嵌套路径的 `refs()` 必须逐段是**用户写的原始 lambda**。
///
/// 回归背景：`Path` 曾 `extends PathRef`，core 于是把 api 的值对象（`PathValues$Nested`）当 lambda
/// 收下，解析属性名时炸 `NoSuchMethodException: writeReplace()`——D1 里 point-mall 唯一失败的根因。
///
/// 这层断言与 [`AssociationConsistencyTest`] 的端到端断言互补：这里证明「路径链可解析」，
/// 那边证明「join / 分页语义对」。
class AssociationPathRefsTest {

    /// 两段 / 单段路径的属性链，以及条件值是否也带完整链。
    @Test
    void nestedPathRefsAreRawLambdas() {
        EntityPath<AssetEntity, OwnerEntity> ownerPath = EntityPath.of(AssetEntity::owner);
        EntityPath<AssetEntity, Long> ownerIdPath = ownerPath.get(OwnerEntity::id);
        StringPath<AssetEntity> ownerNamePath = ownerPath.get(OwnerEntity::name);

        assertThat(propertyNames(ownerIdPath.refs())).containsExactly("owner", "id");
        assertThat(propertyNames(ownerNamePath.refs())).containsExactly("owner", "name");
        assertThat(propertyNames(Path.of(AssetEntity::name).refs())).containsExactly("name");

        Op condition = (Op) ownerIdPath.eqIfNotNull(7L);
        assertThat(propertyNames(condition.refs())).containsExactly("owner", "id");
    }

    /// `refs()` 里绝不能混入 api 的值对象——混入即后端解析必炸。
    @Test
    void refsNeverContainApiValueObjects() {
        StringPath<AssetEntity> ownerNamePath =
                EntityPath.of(AssetEntity::owner).get(OwnerEntity::name);

        for (PathRef<?, ?> ref : ownerNamePath.refs()) {
            assertThat(ref.getClass().getName()).doesNotStartWith("io.github.nextentity.api.");
        }
    }

    /// 用后端同一套机制（`writeReplace` → `SerializedLambda#getImplMethodName`）解析每一段属性名。
    private static List<String> propertyNames(List<PathRef<?, ?>> refs) {
        List<String> names = new ArrayList<>();
        for (PathRef<?, ?> ref : refs) {
            names.add(propertyName(ref));
        }
        return names;
    }

    /// 解析单段属性名。
    private static String propertyName(PathRef<?, ?> ref) {
        try {
            var writeReplace = ref.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            Object lambda = writeReplace.invoke(ref);
            var getImplMethodName = lambda.getClass().getMethod("getImplMethodName");
            return (String) getImplMethodName.invoke(lambda);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("PathRef 不是可解析的 lambda：" + ref.getClass().getName(), e);
        }
    }
}
