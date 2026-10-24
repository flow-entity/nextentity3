package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import jakarta.persistence.EntityNotFoundException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

    /// 实际下发的整句（归一化后）：转换器只在参数值里生效，SQL 文本里看不到明文。
    private static final String INSERT_CONVERTED = "insert into converted (payload,id) values (?,?)";

    private static final String UPDATE_CONVERTED = "update converted ce1_0 set payload=? where ce1_0.id=?";

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

    /// 复位（AGENTS.md：集成测试启动容器后须在 `@AfterEach` 复位被改状态）：删掉本轮写入的行，
    /// 丢掉受管状态与语句账本，容器里的库回到空表。`@BeforeEach` 那条留作兜底——上一条用例
    /// 若崩在复位之前，它保证这一条仍从空表开始。
    @AfterEach
    void resetState() {
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
        assertThat(inserts).containsExactlyElementsOf(Collections.nCopies(DECLARED.size(), INSERT_CONVERTED));

        support.clearSql();
        support.inTransaction(_ -> operations()
                .update(DECLARED.get(0).withPayload("bound-not-inlined").toEntity()));
        support.clear();

        List<String> updates = statements(support.drainSql(), "update converted");
        assertThat(updates).containsExactly(UPDATE_CONVERTED);
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

    /// 归一化成一句话（小写 + 空白压缩）后按前缀筛，断言侧就能整句比对。
    private static List<String> statements(List<String> sql, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return sql.stream()
                .map(WriteRoundTripSupport::normalized)
                .filter(statement -> statement.startsWith(lowered))
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
