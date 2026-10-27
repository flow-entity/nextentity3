-- 建表脚本（SQL Server 2022）：每个接线类一个独立 database（`类名小写`），故 DDL 可以 drop + create 幂等。
-- 不写 COLLATE：夹具与查询参数全小写、无大小写非匹配断言（理由见 tmp/review/design-v2-tester.md §7.2）。
-- 标识符全部裸小写（JDBC 后端渲染裸标识符）；`version`/`status` 在 T-SQL 不是保留字（version 只是 rowversion 别名的类型名）。
drop table if exists asset;
drop table if exists owner;
drop table if exists item;
drop table if exists generated_item;
drop table if exists versioned_item;
drop table if exists ordinal_item;
drop table if exists managed_association;
create table item (id bigint primary key, name varchar(255), score int, score2 int, note varchar(255), status varchar(32));
create table owner (id bigint primary key, name varchar(255));
create table asset (id bigint primary key, name varchar(255), owner_id bigint, constraint fk_asset_owner foreign key (owner_id) references owner (id));
create table generated_item (id bigint identity(1,1) primary key, name varchar(255));
create table versioned_item (id bigint primary key, name varchar(255), version bigint not null default 0);
create table ordinal_item (id bigint primary key, status int);
create table managed_association (id bigint primary key, owner_id bigint);
