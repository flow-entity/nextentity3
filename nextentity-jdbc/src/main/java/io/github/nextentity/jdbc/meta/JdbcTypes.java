package io.github.nextentity.jdbc.meta;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/// 列类型接缝：白名单与两个方向的值转换绑在同一个类里——[#SUPPORTED] 是唯一清单，
/// `supports` 与读写分支都从它派生，新增类型不会出现「白名单放行、读写没接」的漂移。
///
/// 公开原因同 [EnumStorage]：`meta`（列校验）、`compile`（谓词字面量绑定）、`exec`（写入绑定与行读取）三处共用。
///
/// 写侧把 `java.time` / `BigInteger` 转成驱动普遍接受的 `java.sql` / `BigDecimal` 形态：JDBC 4.2 的
/// `setObject`/`getObject` 对时间类型的支持按驱动而异（MySQL 驱动不支持 `getObject(Instant.class)`，
/// 见设计 §7.1 CUT-11），统一走 `Timestamp` / `Date` / `Time` 才能跨四方言一致。
/// 读侧按列的静态 Java 类型收窄；`NULL` 返回 `null`（带类型的 getter 对 `NULL` 直接返回空，不必 `wasNull`）。
@SuppressWarnings("PMD.CyclomaticComplexity") // 读写分支都按 SUPPORTED 白名单穷尽，分支数由列类型集合决定
public final class JdbcTypes {

    /// 受支持的列类型（枚举类型另按 `isEnum()` 放行；存储形态由 `@Enumerated` 决定）。
    ///
    /// `BigInteger` 只为生成键的 JPA 允许类型服务（`short` / `BigInteger` 等），绑定走 [BigDecimal]。
    private static final List<Class<?>> SUPPORTED = List.of(
            String.class,
            Integer.class,
            int.class,
            Long.class,
            long.class,
            Short.class,
            short.class,
            Double.class,
            double.class,
            Float.class,
            float.class,
            Boolean.class,
            boolean.class,
            BigDecimal.class,
            BigInteger.class,
            LocalDate.class,
            LocalDateTime.class,
            LocalTime.class,
            Instant.class,
            Date.class,
            Time.class,
            Timestamp.class);

    private JdbcTypes() {}

    /// 该 Java 类型能否作为列（标量）类型。
    public static boolean supports(Class<?> type) {
        return type.isEnum() || SUPPORTED.contains(type);
    }

    /// 写入 / 谓词字面量：Java 值 → JDBC 可绑定形态。
    ///
    /// `@Version` 的 `null` 视作 0；枚举按 [EnumStorage] 转 `name()` / `ordinal()` 后绑定。
    public static Object toJdbc(Object value, JdbcEntityAttribute attribute) {
        if (value == null) {
            return attribute.isVersion() ? zero(attribute.type()) : null;
        }
        return convert(value, attribute);
    }

    /// 谓词字面量：与 [#toJdbc(Object, JdbcEntityAttribute)] 同一套类型归一，但 `null` 保持 `null`
    /// （`@Version` 的 0 初值只用于写侧，`version = null` 作为查询字面量没有意义）。
    public static Object toLiteral(Object value, JdbcEntityAttribute attribute) {
        return value == null ? null : convert(value, attribute);
    }

    /// `@Version` 自增：`null` → 0，数值型 +1；结果保持字段的包装类型（避免窄化/溢出到别的列类型）。
    ///
    /// 按字段类型分派而不是按值分派：值为 `null` 的非法版本类型也必须拒绝，不能退化成 0。
    ///
    /// @throws UnsupportedOperationException 非 int / long / short 家族的版本类型
    public static Object nextVersion(Object current, Class<?> type) {
        if (type == Integer.class || type == int.class) {
            return current == null ? 0 : ((Number) current).intValue() + 1;
        }
        if (type == Long.class || type == long.class) {
            return current == null ? 0L : ((Number) current).longValue() + 1L;
        }
        if (type == Short.class || type == short.class) {
            return current == null ? (short) 0 : (short) (((Number) current).shortValue() + 1);
        }
        throw new UnsupportedOperationException(JdbcEntityRules.VERSION_UNSUPPORTED + type.getName());
    }

    private static Object convert(Object value, JdbcEntityAttribute attribute) {
        if (attribute.converter() != null) {
            return databaseShape(attribute.converter().convertToDatabaseColumn(value), EnumStorage.NONE);
        }
        return databaseShape(value, attribute.enumStorage());
    }

    /// Java 值 → 驱动普遍接受的绑定形态：枚举按存储形态，`java.time` / `BigInteger` 转 `java.sql` / `BigDecimal`。
    private static Object databaseShape(Object value, EnumStorage storage) {
        if (storage == EnumStorage.NAME) {
            return ((Enum<?>) value).name();
        }
        if (storage == EnumStorage.ORDINAL) {
            return ((Enum<?>) value).ordinal();
        }
        if (value instanceof Instant instant) {
            return Timestamp.from(instant);
        }
        if (value instanceof LocalDate date) {
            return Date.valueOf(date);
        }
        if (value instanceof LocalTime time) {
            return Time.valueOf(time);
        }
        if (value instanceof LocalDateTime dateTime) {
            return Timestamp.valueOf(dateTime);
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        return value;
    }

    /// 行读取：按列的静态 Java 类型从当前行取值（`NULL` 返回 `null`）。
    public static Object read(ResultSet rows, int index, Class<?> type) throws SQLException {
        if (type == Integer.class || type == int.class) {
            int value = rows.getInt(index);
            return rows.wasNull() ? null : value;
        }
        if (type == Long.class || type == long.class) {
            long value = rows.getLong(index);
            return rows.wasNull() ? null : value;
        }
        if (type == Short.class || type == short.class) {
            short value = rows.getShort(index);
            return rows.wasNull() ? null : value;
        }
        if (type == Double.class || type == double.class) {
            double value = rows.getDouble(index);
            return rows.wasNull() ? null : value;
        }
        if (type == Float.class || type == float.class) {
            float value = rows.getFloat(index);
            return rows.wasNull() ? null : value;
        }
        if (type == Boolean.class || type == boolean.class) {
            boolean value = rows.getBoolean(index);
            return rows.wasNull() ? null : value;
        }
        if (type == BigDecimal.class) {
            return rows.getBigDecimal(index);
        }
        if (type == BigInteger.class) {
            BigDecimal value = rows.getBigDecimal(index);
            return value == null ? null : value.toBigInteger();
        }
        if (type == String.class) {
            return rows.getString(index);
        }
        if (type == LocalDate.class) {
            Date value = rows.getDate(index);
            return value == null ? null : value.toLocalDate();
        }
        if (type == LocalDateTime.class) {
            Timestamp value = rows.getTimestamp(index);
            return value == null ? null : value.toLocalDateTime();
        }
        if (type == LocalTime.class) {
            Time value = rows.getTime(index);
            return value == null ? null : value.toLocalTime();
        }
        if (type == Instant.class) {
            Timestamp value = rows.getTimestamp(index);
            return value == null ? null : value.toInstant();
        }
        if (type == Date.class) {
            return rows.getDate(index);
        }
        if (type == Time.class) {
            return rows.getTime(index);
        }
        if (type == Timestamp.class) {
            return rows.getTimestamp(index);
        }
        return rows.getObject(index);
    }

    /// `@Version` 的空初值：数值型绑定对应的包装类型，避免把 `Integer` 塞进 `SMALLINT` 列。
    private static Object zero(Class<?> type) {
        if (type == Long.class || type == long.class) {
            return 0L;
        }
        if (type == Short.class || type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
