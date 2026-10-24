package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// `@Convert` 往返的真方言版：与 [ItemConvertRoundTripTest] 同一套声明与断言，只换连接。
///
/// 真方言多看到的只有一件事：MySQL 的 `payload` 列若被建窄（varchar 长度、字符集），
/// 超长值与 emoji 会在落库或读回时变形——这在 H2 上看不出来，因此这一格是两边都要跑的。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemConvertRoundTripIntegrationTest {

    private static final String STORED_PREFIX = "v1:";

    private static final String WILDCARD_TEXT = "50%_off'quote\\back";

    private static final String UNICODE_TEXT = "名字🎉emoji";

    private static final String LONG_TEXT = "z".repeat(200);

    private static final List<Payload> DECLARED = List.of(
            new Payload(1, "secret"),
            new Payload(2, ""),
            new Payload(3, WILDCARD_TEXT),
            new Payload(4, UNICODE_TEXT),
            new Payload(5, LONG_TEXT),
            new Payload(6, null));

    private final WriteRoundTripSupport<ConvertedEntity> support = WriteRoundTripSupport.onMySql(ConvertedEntity.class);

    @BeforeEach
    void truncateAndClear() {
        support.truncate("ConvertedEntity");
        support.clear();
        support.clearSql();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    @Test
    void insertAllRoundTripsDeclaredPayloads() {
        insertAll(DECLARED);

        assertThat(readByIds(ids(DECLARED))).containsExactlyElementsOf(DECLARED);
        assertThat(storedPayloads(ids(DECLARED)))
                .containsExactlyElementsOf(
                        DECLARED.stream().map(Payload::stored).toList());
    }

    @Test
    void updateAppliesConverterToNewValue() {
        insertAll(DECLARED);
        Payload target = DECLARED.get(2).withPayload("changed-after-update");

        support.inTransaction(_ -> operations().update(target.toEntity()));
        support.clear();

        assertThat(readByIds(List.of(target.id()))).containsExactly(target);
        assertThat(storedPayloads(List.of(target.id()))).containsExactly(STORED_PREFIX + target.payload());
    }

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
