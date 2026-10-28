package io.github.nextentity.testing;

/// 保留字 / 存量表标识符实体的后端中立契约：表名与列名在数据库里是关键字，必须按方言引用。
///
/// 字段名刻意选三个常见关键字（`desc` / `key`），用来证明「引用后的标识符」在查询、
/// 排序、模糊匹配、写入各条路径上都能用；不引用时数据库会拒绝（由各后端 DDL 断言钉住）。
public interface ReservedWord {

    /// 主键。
    ///
    /// @return 主键值
    long id();

    /// 普通文本列（不涉及关键字，用作对照）。
    ///
    /// @return 名称
    String name();

    /// 关键字列：`desc`。
    ///
    /// @return 描述文本
    String desc();

    /// 关键字列：`key`。
    ///
    /// @return 整数键
    Integer key();
}
