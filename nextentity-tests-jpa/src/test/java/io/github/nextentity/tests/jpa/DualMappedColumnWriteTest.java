package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// legacy `Employee` 那个真实回归（H2 通道）：一个标量字段与一个只读关联**映射同一列**时，
/// 写侧必须只写一次该列，且值来自可写的那一侧。
///
/// 回归事实：`JpaPersistExecutor#setValues` 此前只跳过主键与非 owning 关联，`@JoinColumn(updatable = false)`
/// 完全没看，于是标量 `departmentId` 与关联 `department` 各进一次 `SET`，渲染成
/// `set departmentId=?, departmentId=?`，H2/MySQL 报 `Duplicate column name`（legacy 727 条里 10 个错误）。
///
/// oracle 是**「列只出现一次 + 读回值正确」**，不是「不抛异常」：只断言不抛的话，把整个关联写侧
/// 砍掉（连可写关联也不写）同样能绿——`managerDepartmentId` 那条反向对照就是用来挡这个的。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DualMappedColumnWriteTest {

    private static final long EMPLOYEE_ID = 1L;

    private final WriteRoundTripSupport<DualMappedEmployeeEntity> support = WriteRoundTripSupport.onH2(
            "dual_mapped_column", DualMappedEmployeeEntity.class, DualMappedDepartmentEntity.class);

    @BeforeEach
    void seed() {
        support.truncate("DualMappedEmployeeEntity");
        support.truncate("DualMappedDepartmentEntity");
        support.inTransaction(entityManager -> {
            entityManager.persist(new DualMappedDepartmentEntity(1L, "eng"));
            entityManager.persist(new DualMappedDepartmentEntity(2L, "ops"));
            entityManager.persist(new DualMappedDepartmentEntity(3L, "lab"));
            entityManager.persist(new DualMappedEmployeeEntity(EMPLOYEE_ID, "alice", 1L, null, null));
        });
        support.clear();
        support.clearSql();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    /// `insert` 走 Hibernate 自己生成语句：`insertable = false` 的只读关联不该进列清单，
    /// 否则同一列在 INSERT 里也会出现两次（legacy 的 INSERT 路径同样会炸）。
    @Test
    @DisplayName("insert：同列映射的只读关联不进列清单，departmentId 列只出现一次，可写关联照常写")
    void insertWritesSharedColumnOnce() {
        support.inTransaction(entityManager -> entityManager.persist(new DualMappedEmployeeEntity(
                2L, "bob", 2L, null, entityManager.getReference(DualMappedDepartmentEntity.class, 3L))));

        List<String> inserts = statements("insert into dual_mapped_employee");
        assertThat(inserts).as("一次 insert 只能下发一条 INSERT").hasSize(1);
        String insert = inserts.getFirst();
        assertThat(columnOccurrences(insert, "departmentid"))
                .as("标量字段与只读关联映射同一列，INSERT 的列清单里只该有它一次：%s", insert)
                .isEqualTo(1);
        assertThat(insert).as("可写关联（反向对照）必须照常进列清单").contains("managerdepartmentid");

        assertThat(longColumn("departmentId", 2L)).isEqualTo(2L);
        assertThat(longColumn("managerDepartmentId", 2L)).isEqualTo(3L);
    }

    /// 这是被 legacy 抓住的那条：`update(entity)` 的 `SET` 里同一列只写一次，值取标量字段，
    /// 可写关联（`managerDepartmentId`）仍然写。
    @Test
    @DisplayName("update：同列映射的只读关联不进 SET，departmentId 只出现一次且读回值正确")
    void updateWritesSharedColumnOnce() {
        support.clearSql();

        support.inTransaction(entityManager -> support.operations()
                .update(new DualMappedEmployeeEntity(
                        EMPLOYEE_ID, "renamed", 2L, null, new DualMappedDepartmentEntity(3L, "lab"))));

        List<String> updates = statements("update dual_mapped_employee");
        assertThat(updates).as("一次 update 只能下发一条 UPDATE").hasSize(1);
        List<String> assignments = setAssignments(updates.getFirst());
        assertThat(assignments).as("SET 子句里的赋值项").contains("name=?", "departmentid=?", "managerdepartmentid=?");
        assertThat(assignments.stream()
                        .filter(assignment -> assignment.equals("departmentid=?"))
                        .count())
                .as("同一列被标量与只读关联各映射一次时，SET 里只能出现一次：%s", updates.getFirst())
                .isEqualTo(1);

        assertThat(longColumn("departmentId", EMPLOYEE_ID))
                .as("值来自可写的标量字段（关联对象是 null，没参与写）")
                .isEqualTo(2L);
        assertThat(longColumn("managerDepartmentId", EMPLOYEE_ID))
                .as("反向对照：可写关联仍从关联对象取 @Id 写外键")
                .isEqualTo(3L);
    }

    /// 只读列不参与 `SET`，但读回不受影响：`find` 出来的标量字段仍是库里的值。
    @Test
    @DisplayName("读回：只读关联映射的那一列照常读到，不因写侧跳过而变成 null")
    void readBackOfSharedColumnStaysIntact() {
        // 无参 lambda：`em -> em.find(...)` 会匹配到 `inTransaction(Consumer<EntityManager>)`（void 重载），
        // 结果被丢掉；带返回值的形态必须走 `Supplier` 重载。
        DualMappedEmployeeEntity reloaded =
                support.inTransaction(() -> support.entityManager().find(DualMappedEmployeeEntity.class, EMPLOYEE_ID));

        assertThat(reloaded.departmentId()).isEqualTo(1L);
    }

    /// 归一化后的语句里按「整词」数某列出现几次——`managerdepartmentid` 不能算进 `departmentid`。
    private static int columnOccurrences(String statement, String column) {
        Matcher matcher = Pattern.compile("(?<![a-z_])" + Pattern.quote(column) + "(?![a-z_])")
                .matcher(statement);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /// `set` 与 `where` 之间的赋值项，按逗号切开（整句归一化后列序稳定，切分足够）。
    ///
    /// 注意 Hibernate 对 `CriteriaUpdate` 渲染的 `SET` 列表**不带空格**（`set a=?,b=?`），
    /// 所以按 `","` 切再逐项 trim；按 `", "` 切会整段算成一项，`hasSize` 的断言就永远过不了。
    private static List<String> setAssignments(String statement) {
        String normalized = WriteRoundTripSupport.normalized(statement);
        String set =
                normalized.substring(normalized.indexOf(" set ") + " set ".length(), normalized.indexOf(" where "));
        return Arrays.stream(set.split(",")).map(String::trim).toList();
    }

    private List<String> statements(String prefix) {
        return support.drainSql().stream()
                .map(WriteRoundTripSupport::normalized)
                .filter(statement -> statement.startsWith(prefix))
                .toList();
    }

    private long longColumn(String column, long id) {
        Object value = support.inTransaction(() -> support.entityManager()
                .createNativeQuery("select " + column + " from dual_mapped_employee where id = ?")
                .setParameter(1, id)
                .getSingleResult());
        return ((Number) value).longValue();
    }
}
