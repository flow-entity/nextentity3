/// 查询结果模型：{@link io.github.nextentity.api.model.Slice}（一页数据 + 总数）与
/// {@link io.github.nextentity.api.model.Tuple2}（两列投影）。
///
/// 与 `io.github.nextentity.api` 分开，是因为这两个接口只描述「结果长什么样」，不参与链式构建：
/// 后端装配结果时实现它们，调用方只读不实现。
package io.github.nextentity.api.model;
