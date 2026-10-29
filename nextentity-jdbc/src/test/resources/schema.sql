create table if not exists item (id bigint primary key, name varchar(255), score int, score2 int,
                                 note varchar(255), status varchar(32));
create table if not exists owner (id bigint primary key, name varchar(255));
create table if not exists asset (id bigint primary key, name varchar(255), owner_id bigint);
create table if not exists generated_item (id bigint auto_increment primary key, name varchar(255));
create table if not exists integer_generated_item (id int auto_increment primary key, name varchar(255));
create table if not exists versioned_item (id bigint primary key, name varchar(255), version bigint not null default 0);
create table if not exists integer_versioned_item (id bigint primary key, name varchar(255), version int not null default 0);
create table if not exists ordinal_item (id bigint primary key, status int);
create table if not exists ignored_association_item (id bigint primary key);
create table if not exists nullable_id_item (id bigint primary key, name varchar(255));
