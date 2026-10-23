package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.spec.AttributePath;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/// `PathRefs.fieldName` / `PathRefs.declaringClass` 的边界输入。
///
/// 与 `QueryBuilderTest#fieldNameRules` 互补而不重复：那边覆盖常规 getter 名
/// （`getStatus` / `isActive` / `getId` / `getURL` / `status`），这里补它没覆盖的两类边界——
/// **裸前缀**（`"get"`、`"is"`，长度恰好等于前缀长度）与**去前缀后只剩一个字符**（`"getX"`），
/// 并单独驱动 `declaringClass` 的放行侧与拒绝侧。
///
/// 守护的变异体：
/// - `PathRefs.java:61`：`methodName.length() > 3` → `>=`，`"get"` 会被剥成空串，随后在
///   `trimmed.charAt(1)` 处越界；用例断言 `"get"` 原样返回。
/// - `PathRefs.java:63`：`methodName.length() > 2` → `>=`，`"is"` 同上；用例断言 `"is"` 原样返回。
/// - `PathRefs.java:68-69`：`trimmed.length() == 1` 分支返回 `""`（或改为不改大小写）；
///   用例断言 `"getX"` → `"x"`、`"isX"` → `"x"`。
/// - `PathRefs.java:98`：`kind != REF_invokeVirtual && kind != REF_invokeInterface` 条件取反
///   （或 `&&` 改 `||`）；用例既断言实例/接口方法引用被放行，也断言静态/构造引用被拒绝。
@DisplayName("PathRefs 边界")
class PathRefsTest {

    /// 裸前缀不能被当成前缀剥掉；去前缀后只剩一个字符时直接小写。
    @ParameterizedTest(name = "fieldName(\"{0}\") → \"{1}\"")
    @CsvSource({
        "get, get", // 长度恰好 3：`length() > 3` 为假，原样返回（PathRefs.java:61）
        "is, is", // 长度恰好 2：`length() > 2` 为假，原样返回（PathRefs.java:63）
        "getX, x", // 去前缀后只剩 1 个字符：走小写分支（PathRefs.java:68-69）
        "isX, x" // 同上，走 is 前缀分支
    })
    void fieldNameKeepsBarePrefixAndLowercasesSingleChar(String methodName, String expected) {
        assertThat(PathRefs.fieldName(methodName)).isEqualTo(expected);
    }

    /// `declaringClass` 的放行侧：实例方法引用（`REF_invokeVirtual`）的声明类型就是路径起点。
    @Test
    @DisplayName("declaringClass：实例方法引用被放行，起点取第一段的声明类型")
    void declaringClassAcceptsInstanceMethodReference() {
        PathRef<Order, Long> id = Order::getId;

        AttributePath path = PathRefs.resolve(id);

        assertThat(path.entityType()).isEqualTo(Order.class);
        assertThat(path.names()).containsExactly("id");
        assertThat(path.isDirect()).isTrue();
    }

    /// `declaringClass` 的放行侧：接口方法引用（`REF_invokeInterface`）同样被放行。
    @Test
    @DisplayName("declaringClass：接口方法引用被放行")
    void declaringClassAcceptsInterfaceMethodReference() {
        PathRef<CharSequence, Integer> length = CharSequence::length;

        AttributePath path = PathRefs.resolve(length);

        assertThat(path.entityType()).isEqualTo(CharSequence.class);
        assertThat(path.names()).containsExactly("length");
    }

    /// `declaringClass` 的拒绝侧：静态方法引用（`REF_invokeStatic`）不是属性路径。
    @Test
    @DisplayName("declaringClass：静态方法引用被拒绝")
    void declaringClassRejectsStaticMethodReference() {
        PathRef<String, Integer> parseInt = Integer::parseInt;

        assertThatThrownBy(() -> PathRefs.resolve(parseInt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只接受实例方法引用");
    }

    /// `declaringClass` 的拒绝侧：构造引用（`REF_newInvokeSpecial`）不是属性路径。
    @Test
    @DisplayName("declaringClass：构造引用被拒绝")
    void declaringClassRejectsConstructorReference() {
        PathRef<String, String> copy = String::new;

        assertThatThrownBy(() -> PathRefs.resolve(copy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只接受实例方法引用");
    }

    /// 空路径链在 `declaringClass` 之前就被拒绝，保证拒绝理由是可读的入参错误。
    @Test
    @DisplayName("resolve：空属性路径链被拒绝")
    void resolveRejectsEmptyChain() {
        assertThatThrownBy(() -> PathRefs.resolve(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("属性路径链不能为空");
    }

    /// 测试实体：只需要 getter 名，声明类型用作路径起点断言。
    @SuppressWarnings("unused")
    private static final class Order {

        private Long id;

        public Long getId() {
            return id;
        }
    }
}
