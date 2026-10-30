package io.github.nextentity.examples.repository;

import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.examples.entity.Category;
import io.github.nextentity.spring.AbstractRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/// 分类 Repository，继承 {@link AbstractRepository}。
///
/// 用于 {@link Category} 实体的数据访问。
///
/// ## 3.1.0 移植说明
///
/// 3.x 的 {@link AbstractRepository} 只提供 `query()` 与 `insert(T)`；
/// 2.2.3 继承来的 ID 便捷方法（findById/getById/findAllById/findAllAsMapById/existsById/deleteById）
/// 在 3.x 用 `query()` / `core.EntityOperations` 就地表达，见 {@link ProductRepository}。
@Repository
public class CategoryRepository extends AbstractRepository<Category> {

    /// 创建 Repository 实例。
    ///
    /// 通过构造器注入 EntityOperationsFactory，由它按实体类型解析 core.EntityOperations。
    ///
    /// @param context NextEntity 的 EntityOperationsFactory
    protected CategoryRepository(EntityOperationsFactory context) {
        super(context);
    }

    /// 查询所有活跃分类。
    public List<Category> findActiveCategories() {
        return query()
                .where(Category::getActive).eq(true)
                .orderBy(Category::getName).asc()
                .list();
    }

    /// 查询根分类（无父分类）。
    public List<Category> findRootCategories() {
        return query()
                .where(Category::getParentId).isNull()
                .where(Category::getActive).eq(true)
                .orderBy(Category::getName).asc()
                .list();
    }

    /// 根据父分类 ID 查询子分类。
    public List<Category> findSubcategories(Long parentId) {
        return query()
                .where(Category::getParentId).eq(parentId)
                .where(Category::getActive).eq(true)
                .orderBy(Category::getName).asc()
                .list();
    }

    /// 根据名称查找分类。
    public Category findByName(String name) {
        return query()
                .where(Category::getName).eq(name)
                .first();
    }
}