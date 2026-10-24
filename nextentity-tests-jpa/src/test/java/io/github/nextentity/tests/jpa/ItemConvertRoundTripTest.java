package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// `@Convert` 字段的**往返差分**：声明内存形态，读回内存形态，同时用原生查询看落库形态。
///
/// 两条路径分工：内存形态由实体访问器给出（Hibernate 读回落库值后要经过 `convertToEntityAttribute`），
/// 落库形态由原生 SQL 直接取出（绕过一切转换）。期望值两边都只由声明算出——
/// `v1:` 前缀是转换器文档里写死的契约，常量放在用例里，不从转换器取。
///
/// 既有 `ItemConvertConsistencyTest` 用单个值 `secret` 证明了「写/读/谓词三条路都经过转换器」，
/// 这里补的是边界值（null、空串、通配符、emoji、超长）× 批量写 × 更新。
class ItemConvertRoundTripTest {

    /// 转换器落库前缀，见 `PayloadConverter` 的类注释；用例侧独立声明，不从生产代码反射取。
    private static final String STORED_PREFIX = "v1:";

    private static final String WILDCARD_TEXT = "50%_off'quote\\back";

    private static final String UNICODE_TEXT = "名字🎉emoji";

    private static final String LONG_TEXT = "z".repeat(200);

    /// 写入意图：普通值、空串、通配符、emoji、超长、以及 null（落库必须是 SQL NULL 而不是 `v1:null`）。
    private static final List<Payload> DECLARED = List.of(
            new Payload(1, "secret"),
            new Payload(2, ""),
            new Payload(3, WILDCARD_TEXT),
            new Payload(4, UNICODE_TEXT),
            new Payload(5, LONG_TEXT),
            new Payload(6, null));

    private final WriteRoundTripSupport<ConvertedEntity> support =
            WriteRoundTripSupport.onH2("item_convert_round_trip", ConvertedEntity.class);

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 内存形态与落库形态同时比对：批量写经过转换器，读回时再转回来。
    @Test
    void insertAllRoundTripsDeclaredPayloads() {
        insertAll(DECLARED);

        assertThat(readByIds(ids(DECLARED))).containsExactlyElementsOf(DECLARED);
        assertThat(storedPayloads(ids(DECLARED)))
                .containsExactlyElementsOf(
                        DECLARED.stream().map(Payload::stored).toList());
    }

    /// 更新也要经过转换器：SET 里绑的是落库形态，而不是把内存值直接写进列。
    ///
    /// 若转换只做在 insert 路径上，这条会红——而这正是「加了 `@Convert` 之后旧值读得出来、
    /// 新值写进去读回是 `v1:xxx`」那类线上现象。
    @Test
    void updateAppliesConverterToNewValue() {
        insertAll(DECLARED);
        Payload target = DECLARED.get(2).withPayload("changed-after-update");

        support.inTransaction(_ -> operations().update(target.toEntity()));
        support.clear();

        assertThat(readByIds(List.of(target.id()))).containsExactly(target);
        assertThat(storedPayloads(List.of(target.id()))).containsExactly(STORED_PREFIX + target.payload());
    }

    /// 语句骨架：`insert into converted` 一行一条且带 `payload` 列；`update converted … set` 带主键条件，
    /// 且值走的是绑定参数（SQL 文本里看不到新值本身）。
    @Test
    void statementsCarryPayloadColumnAndBindTheValue() {
        insertAll(DECLARED);

        List<String> inserts = statements(support.drainSql(), "insert into converted");
        assertThat(inserts).hasSize(DECLARED.size());
        assertThat(inserts)
                .allSatisfy(sql -> assertThat(sql).contains("payload").contains("values ("));

        support.clearSql();
        support.inTransaction(_ -> operations()
                .update(DECLARED.get(0).withPayload("bound-not-inlined").toEntity()));
        support.clear();

        List<String> updates = statements(support.drainSql(), "update converted");
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0))
                .contains("payload")
                .contains("where ")
                .contains("id=?")
                .doesNotContain("bound-not-inlined");
    }

    /// 更新不存在的行必须拒绝，而不是静默当成 upsert。
    ///
    /// `ConvertedEntity` 没有 `@Version`，所以 `JpaPersistExecutor` 走的是「影响行数 ≠ 1 且无版本列 →
    /// `EntityNotFoundException`」那条分支，文案由本库自己拼（`实体 <类名> 不存在: id=99`）。
    @Test
    void updateOfMissingRowIsRejected() {
        assertThatThrownBy(() -> support.inTransaction(_ -> operations().update(new ConvertedEntity(99L, "ghost"))))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("不存在: id=99");
    }

    private EntityOperations<ConvertedEntity> operations() {
        return support.operations();
    }

    private void insertAll(List<Payload> payloads) {
        support.inTransaction(_ ->
                operations().insertAll(payloads.stream().map(Payload::toEntity).toList()));
        support.clear();
    }

    private List<Payload> readByIds(List<Long> ids) {
        support.clear();
        PathRef<ConvertedEntity, Long> id = ConvertedEntity.idRef();
        BaseWhereStep<ConvertedEntity, ConvertedEntity> filtered =
                operations().query().where(id).in(ids);
        return filtered.orderBy(ConvertedEntity.idRef()).asc().list().stream()
                .map(Payload::of)
                .toList();
    }

    /// 落库形态：原生 SQL 直接取列，经过任何转换器。
    private List<String> storedPayloads(List<Long> ids) {
        support.clear();
        return ids.stream()
                .map(id -> (String) support.entityManager()
                        .createNativeQuery("select payload from converted where id = ?")
                        .setParameter(1, id)
                        .getSingleResult())
                .toList();
    }

    private static List<Long> ids(List<Payload> payloads) {
        return payloads.stream().map(Payload::id).toList();
    }

    private static List<String> statements(List<String> sql, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return sql.stream()
                .filter(statement -> statement.toLowerCase(Locale.ROOT).startsWith(lowered))
                .toList();
    }

    /// 一条写入意图：内存形态 → 期望的内存形态（`of`）与期望的落库形态（`stored`），两者都由声明算出。
    private record Payload(long id, String payload) {

        String stored() {
            return payload == null ? null : STORED_PREFIX + payload;
        }

        Payload withPayload(String newPayload) {
            return new Payload(id, newPayload);
        }

        ConvertedEntity toEntity() {
            return new ConvertedEntity(id, payload);
        }

        static Payload of(ConvertedEntity entity) {
            return new Payload(entity.id(), entity.payload());
        }
    }
}
