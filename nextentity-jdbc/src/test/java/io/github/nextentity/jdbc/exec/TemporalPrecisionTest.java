package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.jdbc.support.TimesItem;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 时间列的**写入形态与读回形态必须对称**：写进库的值与读回的值逐字段相等。
///
/// 表用 `time(9)` / `timestamp(9)` 建，精度由列宽给足，因此本用例只钉「本层（驱动绑定 + 取值）
/// 有没有把列装得下的精度丢掉」——列宽不够导致的截断不属于本层。
///
/// 同族的 `LocalDateTime` / `Instant` 用同一张表做对照：它们的绑定形态是 `Timestamp`（保到纳秒），
/// 若只有 `LocalTime` 丢精度，那就是这一种类型的绑定形态选错了，而不是「时间类型普遍不支持」。
class TemporalPrecisionTest {

    private static final String URL = "jdbc:h2:mem:temporal_precision;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private final JdbcDataSource dataSource = dataSource();
    private final EntityOperations<TimesItem> operations =
            new JdbcEntityOperationsFactory(dataSource).operations(TimesItem.class);

    @BeforeEach
    void schema() throws SQLException {
        execute("drop table if exists times_item");
        execute("create table times_item (id bigint primary key, birthDate date, createdAt timestamp(9),"
                + " wakeTime time(9), syncedAt timestamp(9), sqlDate date, sqlTime time,"
                + " sqlTimestamp timestamp(9))");
    }

    @Test
    @DisplayName("LocalTime 写入后读回同一个值：列装得下的小数秒不能在绑定/取值时丢掉")
    void localTimeRoundTripKeepsFractionalSeconds() {
        LocalTime wakeTime = LocalTime.of(6, 30, 15, 123_456_789);
        operations.insert(item(wakeTime));

        TimesItem reloaded =
                operations.query().where(Path.of(TimesItem::id)).eq(1L).single();

        assertThat(reloaded.wakeTime()).isEqualTo(wakeTime);
    }

    @Test
    @DisplayName("对照：同一张表上 LocalDateTime / Instant 的小数秒不丢（证明缺口只在 LocalTime）")
    void otherTemporalTypesKeepFractionalSeconds() {
        LocalDateTime createdAt = LocalDateTime.of(2024, 3, 4, 5, 6, 7, 123_456_789);
        Instant syncedAt = Instant.parse("2024-01-02T03:04:05.123456789Z");
        operations.insert(item(createdAt, syncedAt));

        TimesItem reloaded =
                operations.query().where(Path.of(TimesItem::id)).eq(1L).single();

        assertThat(reloaded.createdAt()).isEqualTo(createdAt);
        assertThat(reloaded.syncedAt()).isEqualTo(syncedAt);
    }

    private static TimesItem item(LocalTime wakeTime) {
        return item(LocalDateTime.of(2024, 3, 4, 5, 6, 7), Instant.parse("2024-01-02T03:04:05Z"), wakeTime);
    }

    private static TimesItem item(LocalDateTime createdAt, Instant syncedAt) {
        return item(createdAt, syncedAt, LocalTime.of(6, 30));
    }

    private static TimesItem item(LocalDateTime createdAt, Instant syncedAt, LocalTime wakeTime) {
        return new TimesItem(
                1L,
                LocalDate.of(1990, 1, 2),
                createdAt,
                wakeTime,
                syncedAt,
                Date.valueOf(LocalDate.of(2020, 5, 6)),
                Time.valueOf(LocalTime.of(7, 8, 9)),
                Timestamp.valueOf(LocalDateTime.of(2021, 6, 7, 8, 9, 10)));
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(URL);
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
