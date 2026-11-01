package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.entity.ContactInfo;
import io.github.nextentity.jpa.entity.EmbeddedAssociationFlags;
import io.github.nextentity.jpa.entity.Organization;
import io.github.nextentity.jpa.exec.JpaExecutor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `@Embedded` 里的单值关联（`contact.country`）必须能作为**路径中段**继续 join：
/// `EntityPath.of(X::getContact).get(ContactInfo::getCountry).get(Organization::getName)`
/// 是公开 API 可写的形态，条件落在被关联表的列上。
///
/// 此前 `JpaPaths.navigate` 对 `@Embedded` 段走 `get(...)`（拿到的是嵌入路径，不是 `From`），
/// 下一段是关联时无条件强转 `From` → 裸 `ClassCastException`（消息里没有实体/属性名）。
///
/// 两条护栏：
/// - **LEFT 语义差分**：嵌入为 `null` 的行必须保住。Hibernate 对「嵌入段 `get` + 关联段 `get`」
///   补的是 **INNER** join（实测：`or` 里另一支本该命中 null 嵌入行也被丢掉），所以这条用例
///   专门用 `or` 构造「只有左连接才命中两行」的形态；
/// - **渲染形态**：语句里必须是 `left join`，不是 `join`。
class JpaEmbeddedAssociationPathTest extends JpaTestSupport {

    private static final AttributePath COUNTRY_NAME =
            AttributePath.of(EmbeddedAssociationFlags.class, "contact", "country", "name");

    private static final AttributePath LABEL = AttributePath.of(EmbeddedAssociationFlags.class, "label");

    private static final AttributePath ID = AttributePath.of(EmbeddedAssociationFlags.class, "id");

    @BeforeEach
    void seedEmbeddedAssociations() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from EmbeddedAssociationFlags").executeUpdate();
            entityManager.persist(new EmbeddedAssociationFlags(
                    1L, "acme", new ContactInfo("x", entityManager.getReference(Organization.class, 1L), null), null));
            entityManager.persist(new EmbeddedAssociationFlags(2L, "no-contact", null, null));
            entityManager.persist(new EmbeddedAssociationFlags(
                    3L, "empty", new ContactInfo("y", entityManager.getReference(Organization.class, 2L), null), null));
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("条件落在 @Embedded 里的 to-one 关联列上：能 join 出被关联表的列")
    void conditionThroughEmbeddedAssociationJoinsTheTargetTable() {
        List<Long> ids = idsOf(new Comparison(COUNTRY_NAME, ComparisonOperator.EQ, Literal.of("Acme")));

        assertThat(ids).containsExactly(1L);
        assertThat(normalized(drainSql()))
                .as("嵌入段与关联段都要 join，且必须是 LEFT（嵌入为 null 的行不能被静默过滤掉）")
                .anySatisfy(sql -> assertThat(sql).contains("left join organization"));
    }

    @Test
    @DisplayName("LEFT 语义：嵌入为 null 的行在 or 的另一支命中时必须保住")
    void nullEmbeddedRowSurvivesAnOrBranch() {
        Spec predicate = new Logical(
                LogicalOperator.OR,
                List.of(
                        new Comparison(COUNTRY_NAME, ComparisonOperator.EQ, Literal.of("Acme")),
                        new Comparison(LABEL, ComparisonOperator.EQ, Literal.of("no-contact"))));

        List<Long> ids = idsOf(predicate);

        assertThat(ids)
                .as("Hibernate 对「嵌入 get + 关联 get」补的是 INNER join，会把 2 号行丢掉；LEFT join 才保住")
                .containsExactlyInAnyOrder(1L, 2L);
    }

    private List<Long> idsOf(Spec where) {
        QuerySpec spec = new QuerySpec(
                EmbeddedAssociationFlags.class,
                new EntitySelection(),
                where,
                List.of(),
                null,
                List.of(new Ordering(ID, Ordering.Direction.ASC)),
                Window.NONE,
                null);
        return inTx(entityManager -> {
            JpaCompiler compiler = new JpaCompiler(entityManager, meta());
            JpaExecutor executor = new JpaExecutor(entityManager, meta());
            return executor.<EmbeddedAssociationFlags>list(compiler.compile(spec)).stream()
                    .map(EmbeddedAssociationFlags::getId)
                    .toList();
        });
    }
}
