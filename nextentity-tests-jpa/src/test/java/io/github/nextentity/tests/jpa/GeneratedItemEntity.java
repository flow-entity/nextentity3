package io.github.nextentity.tests.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 自增主键的被查实体：`id` 由库生成（`IDENTITY`），调用方**不**给主键。
///
/// 此前全部非遗留用例的实体都是「应用侧给主键」（`Long id` 无 `@GeneratedValue`），
/// 于是「主键生成策略」这一整个维度零覆盖：`PersistExecutor#insert` 返回 `void`，
/// 主键只能靠 JPA 把生成值回写到**同一个实例**上（`entityManager.persist` 的语义），
/// 换成一个新建实例、或改成 `merge`、或把 `@GeneratedValue` 的列塞进 `CriteriaUpdate` 的
/// SET 子句，都不会有任何现有用例变红。
///
/// 主键类型刻意用包装类型 `Long`：`IDENTITY` 下 Hibernate 在 `persist` 那一刻就执行
/// INSERT 并回填主键，包装类型才能区分「回填了」与「回填成 null」。
@Entity(name = "GeneratedItemEntity")
@Table(name = "generated_item")
public class GeneratedItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected GeneratedItemEntity() {}

    public GeneratedItemEntity(String name) {
        this.name = name;
    }

    /// 带主键的构造器：给「主键已由数据库生成、随后只做 update」的用例用。
    ///
    /// `@GeneratedValue` 只约束 `persist` 时由谁给主键；`update` 走的是 `CriteriaUpdate`，
    /// 主键由调用方带上，所以这条路径必须能显式指定 id。
    public GeneratedItemEntity(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }
}
