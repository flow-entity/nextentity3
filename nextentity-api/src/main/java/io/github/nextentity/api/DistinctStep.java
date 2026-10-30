package io.github.nextentity.api;

/// 去重修饰符：让**随后那一次**投影带上 `SELECT DISTINCT`。
///
/// 去重从"投影形态"里正交出来，因此单列、双列 `Tuple2`、3 列及以上 `Tuple`、
/// [SelectStep#select(Class)] 的 DTO 投影，以及 [SelectStep#project(ProjectionMapper)] 五种投影
/// 一视同仁，不需要为每种形态各配一个去重重载（`selectDistinct` 就写作 `distinct().select(Dto.class)`）。
///
/// 返回 [SelectStep] 而不是后续步骤：旗标必须由紧随其后的投影消费掉，调用方拿不到
/// `list()` / `where(...)`，所以"声明了去重却没投影"在编译期就不可写，不需要运行期再校验一次。
///
/// `SELECT DISTINCT 实体`仍然表达不出来：`EntityQuery` 能调 [distinct()]，但紧随其后的
/// [SelectStep#select(Class)] 若是实体类型本身，整实体查询的列集不是显式列表，运行期抛
/// `UnsupportedOperationException`（而不是让旗标静默失效）。
///
/// @param <T> 根实体类型
public interface DistinctStep<T> {

    /// 让下一次投影去重。
    ///
    /// 去重发生在数据库侧（`SELECT DISTINCT`），`null` 算一个分组。旗标只作用于紧随其后的
    /// 那一次投影，被消费后即失效——在同一个查询入口上再次投影会回到不去重。
    ///
    /// 多列去重时 `count()` 抛 `UnsupportedOperationException`：`COUNT(DISTINCT a, b)` 在
    /// MySQL 与 PostgreSQL 形态不同，只数第一列会静默给出错数。
    ///
    /// @return 投影入口：紧接着调用一次投影即可，中间不能插入其它步骤
    SelectStep<T> distinct();
}
