package io.github.nextentity.testing;

/// 关联嵌套用例的夹具描述：谁、叫什么、归哪个 owner（`ownerName` 为 `null` 表示没有关联）。
///
/// 用描述而不是实体实例，是为了让一致性基类不必知道后端实体怎么构造、owner 怎么落库。
///
/// @param id        主键
/// @param name      名称
/// @param ownerName owner 名称，`null` 表示无关联
public record AssetFixture(long id, String name, String ownerName) {}
