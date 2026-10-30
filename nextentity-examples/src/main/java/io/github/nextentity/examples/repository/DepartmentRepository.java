package io.github.nextentity.examples.repository;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.examples.entity.Department;
import io.github.nextentity.spring.AbstractRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/// 部门 Repository。
///
/// 用于 {@link Department} 实体的数据访问。
///
/// ## 嵌套路径查询支持
///
/// 嵌套属性用 `EntityPath.of(...)` 表达（3.1.0 没有 Entity 标记接口）：
/// ```java
/// // 在 EmployeeRepository 中
/// List<Employee> employees = employeeRepository.query()
///     .where(EntityPath.of(Employee::getDepartment).get(Department::getName)).eq("技术部")
///     .list();
/// ```
@Repository
public class DepartmentRepository extends AbstractRepository<Department> {

    /// 3.x 的 [AbstractRepository] 只保留 query()/insert(T)，写操作走 core.PersistExecutor。
    private final EntityOperations<Department> operations;

    /// 创建 Repository 实例。
    ///
    /// 通过构造器注入 EntityOperationsFactory，由它按实体类型解析 core.EntityOperations。
    ///
    /// @param context NextEntity 的 EntityOperationsFactory
    protected DepartmentRepository(EntityOperationsFactory context) {
        super(context);
        this.operations = context.operations(Department.class);
    }

    /// 批量插入。
    @Transactional
    public void insertAll(Iterable<Department> departments) {
        operations.insertAll(departments);
    }

    /// 批量按实体删除：3.x 没有 delete(T)/deleteAll，用主键条件删除表达。
    @Transactional
    public void deleteAll(Iterable<Department> departments) {
        List<Long> ids = new java.util.ArrayList<>();
        departments.forEach(d -> ids.add(d.getId()));
        if (ids.isEmpty()) {
            return;
        }
        operations.delete().where(Department::getId).in(ids).execute();
    }

    @Override
    public EntityQuery<Department> query() {
        return super.query();
    }

    // ==================== 基本 CRUD 操作 ====================

    /// 查询所有活跃部门。
    public List<Department> findActiveDepartments() {
        return query()
                .where(Department::getActive).eq(true)
                .orderBy(Department::getName).asc()
                .list();
    }

    /// 根据ID查找部门。
    public Department findDepartmentById(Long id) {
        return query()
                .where(Department::getId).eq(id)
                .first();
    }

    // ==================== 投影 ====================

    /// 选择活跃部门的名称列表。
    public List<String> findActiveDepartmentNames() {
        return query()
                .select(Department::getName)
                .where(Department::getActive).eq(true)
                .list();
    }

    /// 查询部门信息到 DTO。
    public List<DepartmentInfo> findDepartmentInfo() {
        return query()
                .select(DepartmentInfo.class)
                .where(Department::getActive).eq(true)
                .list();
    }

    // ==================== DTO 类 ====================

    /// 部门信息 DTO。
    public static class DepartmentInfo {
        private Long id;
        private String name;
        private String location;
        private Double budget;

        public DepartmentInfo() {}

        public DepartmentInfo(Long id, String name, String location, Double budget) {
            this.id = id;
            this.name = name;
            this.location = location;
            this.budget = budget;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public Double getBudget() { return budget; }
        public void setBudget(Double budget) { this.budget = budget; }
    }
}