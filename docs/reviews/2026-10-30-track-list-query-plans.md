# 歌曲列表真实 MySQL EXPLAIN

数据：12000 行，三状态各 4000；重复标题、艺术家与时间。内容投影与业务查询相同，无 ID3。MySQL 8.4，无 FORCE INDEX。

## V7：原直接投影查询

| 筛选 | 排序 | offset | table | type | key | rows | Extra |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 全部 | id asc | 0 | tracks | index | PRIMARY | 20 | null |
| 全部 | id asc | 10000 | tracks | index | PRIMARY | 10020 | null |
| 全部 | id desc | 0 | tracks | index | PRIMARY | 20 | Backward index scan |
| 全部 | id desc | 10000 | tracks | index | PRIMARY | 10020 | Backward index scan |
| 全部 | title asc, id asc | 0 | tracks | index | ix_tracks_title | 20 | null |
| 全部 | title asc, id asc | 10000 | tracks | ALL | null | 11802 | Using filesort |
| 全部 | title desc, id desc | 0 | tracks | index | ix_tracks_title | 20 | Backward index scan |
| 全部 | title desc, id desc | 10000 | tracks | ALL | null | 11802 | Using filesort |
| 全部 | artist_name asc, id asc | 0 | tracks | index | ix_tracks_artist_name | 20 | null |
| 全部 | artist_name asc, id asc | 10000 | tracks | ALL | null | 11802 | Using filesort |
| 全部 | artist_name desc, id desc | 0 | tracks | index | ix_tracks_artist_name | 20 | Backward index scan |
| 全部 | artist_name desc, id desc | 10000 | tracks | ALL | null | 11802 | Using filesort |
| 全部 | created_at asc, id asc | 0 | tracks | ALL | null | 11802 | Using filesort |
| 全部 | created_at asc, id asc | 10000 | tracks | ALL | null | 11802 | Using filesort |
| 全部 | created_at desc, id desc | 0 | tracks | ALL | null | 11802 | Using filesort |
| 全部 | created_at desc, id desc | 10000 | tracks | ALL | null | 11802 | Using filesort |
| 全部 | COUNT | — | tracks | index | fk_tracks_created_by | 11802 | Using index |
| PUBLISHED | id asc | 0 | tracks | ref | ix_tracks_status | 4000 | null |
| PUBLISHED | id asc | 10000 | tracks | ref | ix_tracks_status | 4000 | null |
| PUBLISHED | id desc | 0 | tracks | ref | ix_tracks_status | 4000 | Backward index scan |
| PUBLISHED | id desc | 10000 | tracks | ref | ix_tracks_status | 4000 | Backward index scan |
| PUBLISHED | title asc, id asc | 0 | tracks | index | ix_tracks_title | 59 | Using where |
| PUBLISHED | title asc, id asc | 10000 | tracks | ref | ix_tracks_status | 4000 | Using filesort |
| PUBLISHED | title desc, id desc | 0 | tracks | index | ix_tracks_title | 59 | Using where; Backward index scan |
| PUBLISHED | title desc, id desc | 10000 | tracks | ref | ix_tracks_status | 4000 | Using filesort |
| PUBLISHED | artist_name asc, id asc | 0 | tracks | index | ix_tracks_artist_name | 59 | Using where |
| PUBLISHED | artist_name asc, id asc | 10000 | tracks | ref | ix_tracks_status | 4000 | Using filesort |
| PUBLISHED | artist_name desc, id desc | 0 | tracks | index | ix_tracks_artist_name | 59 | Using where; Backward index scan |
| PUBLISHED | artist_name desc, id desc | 10000 | tracks | ref | ix_tracks_status | 4000 | Using filesort |
| PUBLISHED | created_at asc, id asc | 0 | tracks | ref | ix_tracks_status | 4000 | Using filesort |
| PUBLISHED | created_at asc, id asc | 10000 | tracks | ref | ix_tracks_status | 4000 | Using filesort |
| PUBLISHED | created_at desc, id desc | 0 | tracks | ref | ix_tracks_status | 4000 | Using filesort |
| PUBLISHED | created_at desc, id desc | 10000 | tracks | ref | ix_tracks_status | 4000 | Using filesort |
| PUBLISHED | COUNT | — | tracks | ref | ix_tracks_status | 4000 | Using index |

## V8：索引分页 ID 加本页回表

| 筛选 | 排序 | offset | table | type | key | rows | Extra |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 全部 | id asc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| 全部 | id asc | 0 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | id asc | 0 | i | index | PRIMARY | 20 | Using index |
| 全部 | id asc | 10000 | <derived2> | ALL | null | 10020 | Using temporary; Using filesort |
| 全部 | id asc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | id asc | 10000 | i | index | PRIMARY | 10020 | Using index |
| 全部 | id desc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| 全部 | id desc | 0 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | id desc | 0 | i | index | PRIMARY | 20 | Backward index scan; Using index |
| 全部 | id desc | 10000 | <derived2> | ALL | null | 10020 | Using temporary; Using filesort |
| 全部 | id desc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | id desc | 10000 | i | index | PRIMARY | 10020 | Backward index scan; Using index |
| 全部 | title asc, id asc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| 全部 | title asc, id asc | 0 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | title asc, id asc | 0 | i | index | ix_tracks_title | 20 | Using index |
| 全部 | title asc, id asc | 10000 | <derived2> | ALL | null | 10020 | Using temporary; Using filesort |
| 全部 | title asc, id asc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | title asc, id asc | 10000 | i | index | ix_tracks_title | 10020 | Using index |
| 全部 | title desc, id desc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| 全部 | title desc, id desc | 0 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | title desc, id desc | 0 | i | index | ix_tracks_title | 20 | Backward index scan; Using index |
| 全部 | title desc, id desc | 10000 | <derived2> | ALL | null | 10020 | Using temporary; Using filesort |
| 全部 | title desc, id desc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | title desc, id desc | 10000 | i | index | ix_tracks_title | 10020 | Backward index scan; Using index |
| 全部 | artist_name asc, id asc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| 全部 | artist_name asc, id asc | 0 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | artist_name asc, id asc | 0 | i | index | ix_tracks_artist_name | 20 | Using index |
| 全部 | artist_name asc, id asc | 10000 | <derived2> | ALL | null | 10020 | Using temporary; Using filesort |
| 全部 | artist_name asc, id asc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | artist_name asc, id asc | 10000 | i | index | ix_tracks_artist_name | 10020 | Using index |
| 全部 | artist_name desc, id desc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| 全部 | artist_name desc, id desc | 0 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | artist_name desc, id desc | 0 | i | index | ix_tracks_artist_name | 20 | Backward index scan; Using index |
| 全部 | artist_name desc, id desc | 10000 | <derived2> | ALL | null | 10020 | Using temporary; Using filesort |
| 全部 | artist_name desc, id desc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | artist_name desc, id desc | 10000 | i | index | ix_tracks_artist_name | 10020 | Backward index scan; Using index |
| 全部 | created_at asc, id asc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| 全部 | created_at asc, id asc | 0 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | created_at asc, id asc | 0 | i | index | ix_tracks_created_at | 20 | Using index |
| 全部 | created_at asc, id asc | 10000 | <derived2> | ALL | null | 10020 | Using temporary; Using filesort |
| 全部 | created_at asc, id asc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | created_at asc, id asc | 10000 | i | index | ix_tracks_created_at | 10020 | Using index |
| 全部 | created_at desc, id desc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| 全部 | created_at desc, id desc | 0 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | created_at desc, id desc | 0 | i | index | ix_tracks_created_at | 20 | Backward index scan; Using index |
| 全部 | created_at desc, id desc | 10000 | <derived2> | ALL | null | 10020 | Using temporary; Using filesort |
| 全部 | created_at desc, id desc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| 全部 | created_at desc, id desc | 10000 | i | index | ix_tracks_created_at | 10020 | Backward index scan; Using index |
| 全部 | COUNT | — | tracks | index | fk_tracks_created_by | 12099 | Using index |
| PUBLISHED | id asc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| PUBLISHED | id asc | 0 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | id asc | 0 | i | ref | ix_tracks_status | 4000 | Using index |
| PUBLISHED | id asc | 10000 | <derived2> | ALL | null | 4000 | Using temporary; Using filesort |
| PUBLISHED | id asc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | id asc | 10000 | i | ref | ix_tracks_status | 4000 | Using index |
| PUBLISHED | id desc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| PUBLISHED | id desc | 0 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | id desc | 0 | i | ref | ix_tracks_status | 4000 | Backward index scan; Using index |
| PUBLISHED | id desc | 10000 | <derived2> | ALL | null | 4000 | Using temporary; Using filesort |
| PUBLISHED | id desc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | id desc | 10000 | i | ref | ix_tracks_status | 4000 | Backward index scan; Using index |
| PUBLISHED | title asc, id asc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| PUBLISHED | title asc, id asc | 0 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | title asc, id asc | 0 | i | ref | ix_tracks_status_title | 4000 | Using index |
| PUBLISHED | title asc, id asc | 10000 | <derived2> | ALL | null | 4000 | Using temporary; Using filesort |
| PUBLISHED | title asc, id asc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | title asc, id asc | 10000 | i | ref | ix_tracks_status_title | 4000 | Using index |
| PUBLISHED | title desc, id desc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| PUBLISHED | title desc, id desc | 0 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | title desc, id desc | 0 | i | ref | ix_tracks_status_title | 4000 | Backward index scan; Using index |
| PUBLISHED | title desc, id desc | 10000 | <derived2> | ALL | null | 4000 | Using temporary; Using filesort |
| PUBLISHED | title desc, id desc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | title desc, id desc | 10000 | i | ref | ix_tracks_status_title | 4000 | Backward index scan; Using index |
| PUBLISHED | artist_name asc, id asc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| PUBLISHED | artist_name asc, id asc | 0 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | artist_name asc, id asc | 0 | i | ref | ix_tracks_status_artist_name | 4000 | Using index |
| PUBLISHED | artist_name asc, id asc | 10000 | <derived2> | ALL | null | 4000 | Using temporary; Using filesort |
| PUBLISHED | artist_name asc, id asc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | artist_name asc, id asc | 10000 | i | ref | ix_tracks_status_artist_name | 4000 | Using index |
| PUBLISHED | artist_name desc, id desc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| PUBLISHED | artist_name desc, id desc | 0 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | artist_name desc, id desc | 0 | i | ref | ix_tracks_status_artist_name | 4000 | Backward index scan; Using index |
| PUBLISHED | artist_name desc, id desc | 10000 | <derived2> | ALL | null | 4000 | Using temporary; Using filesort |
| PUBLISHED | artist_name desc, id desc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | artist_name desc, id desc | 10000 | i | ref | ix_tracks_status_artist_name | 4000 | Backward index scan; Using index |
| PUBLISHED | created_at asc, id asc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| PUBLISHED | created_at asc, id asc | 0 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | created_at asc, id asc | 0 | i | ref | ix_tracks_status_created_at | 4000 | Using index |
| PUBLISHED | created_at asc, id asc | 10000 | <derived2> | ALL | null | 4000 | Using temporary; Using filesort |
| PUBLISHED | created_at asc, id asc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | created_at asc, id asc | 10000 | i | ref | ix_tracks_status_created_at | 4000 | Using index |
| PUBLISHED | created_at desc, id desc | 0 | <derived2> | ALL | null | 20 | Using temporary; Using filesort |
| PUBLISHED | created_at desc, id desc | 0 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | created_at desc, id desc | 0 | i | ref | ix_tracks_status_created_at | 4000 | Backward index scan; Using index |
| PUBLISHED | created_at desc, id desc | 10000 | <derived2> | ALL | null | 4000 | Using temporary; Using filesort |
| PUBLISHED | created_at desc, id desc | 10000 | t | eq_ref | PRIMARY | 1 | null |
| PUBLISHED | created_at desc, id desc | 10000 | i | ref | ix_tracks_status_created_at | 4000 | Backward index scan; Using index |
| PUBLISHED | COUNT | — | tracks | ref | ix_tracks_status | 4000 | Using index |

## V8 实际执行分析

EXPLAIN 的 rows 为估计值；以下 TREE 记录 actual rows，核对 LIMIT 后回表与外层排序仅处理本页。耗时只代表测试环境，不作为 SLA。

### 全部 / title / offset=10000

```text
-> Sort: t.title, t.id  (actual time=6.72..6.72 rows=20 loops=1)
    -> Stream results  (cost=10837 rows=20) (actual time=4.63..6.69 rows=20 loops=1)
        -> Nested loop inner join  (cost=10837 rows=20) (actual time=4.63..6.67 rows=20 loops=1)
            -> Table scan on page_ids  (cost=912..914 rows=20) (actual time=4.26..4.26 rows=20 loops=1)
                -> Materialize  (cost=911..911 rows=20) (actual time=4.26..4.26 rows=20 loops=1)
                    -> Limit/Offset: 20/10000 row(s)  (cost=909 rows=20) (actual time=4.24..4.24 rows=20 loops=1)
                        -> Covering index scan on i using ix_tracks_title  (cost=909 rows=10020) (actual time=0.512..3.97 rows=10020 loops=1)
            -> Single-row index lookup on t using PRIMARY (id=page_ids.id)  (cost=0.99 rows=1) (actual time=0.12..0.12 rows=1 loops=20)

```

### 全部 / createdAt / offset=10000

```text
-> Sort: t.created_at, t.id  (actual time=2.22..2.22 rows=20 loops=1)
    -> Stream results  (cost=9541 rows=20) (actual time=1.49..2.19 rows=20 loops=1)
        -> Nested loop inner join  (cost=9541 rows=20) (actual time=1.49..2.17 rows=20 loops=1)
            -> Table scan on page_ids  (cost=901..904 rows=20) (actual time=1.19..1.2 rows=20 loops=1)
                -> Materialize  (cost=901..901 rows=20) (actual time=1.19..1.19 rows=20 loops=1)
                    -> Limit/Offset: 20/10000 row(s)  (cost=899 rows=20) (actual time=1.18..1.18 rows=20 loops=1)
                        -> Covering index scan on i using ix_tracks_created_at  (cost=899 rows=10020) (actual time=0.0391..0.895 rows=10020 loops=1)
            -> Single-row index lookup on t using PRIMARY (id=page_ids.id)  (cost=0.862 rows=1) (actual time=0.0486..0.0486 rows=1 loops=20)

```

### PUBLISHED / title / offset=100

```text
-> Sort: t.title, t.id  (actual time=3.4..3.4 rows=20 loops=1)
    -> Stream results  (cost=741 rows=20) (actual time=0.326..3.38 rows=20 loops=1)
        -> Nested loop inner join  (cost=741 rows=20) (actual time=0.323..3.35 rows=20 loops=1)
            -> Table scan on page_ids  (cost=639..641 rows=20) (actual time=0.0419..0.0468 rows=20 loops=1)
                -> Materialize  (cost=638..638 rows=20) (actual time=0.041..0.041 rows=20 loops=1)
                    -> Limit/Offset: 20/100 row(s)  (cost=636 rows=20) (actual time=0.03..0.0316 rows=20 loops=1)
                        -> Covering index lookup on i using ix_tracks_status_title (status='PUBLISHED')  (cost=636 rows=4000) (actual time=0.0134..0.0275 rows=120 loops=1)
            -> Single-row index lookup on t using PRIMARY (id=page_ids.id)  (cost=0.813 rows=1) (actual time=0.165..0.165 rows=1 loops=20)

```

### PUBLISHED / createdAt / offset=100

```text
-> Sort: t.created_at, t.id  (actual time=0.938..0.939 rows=20 loops=1)
    -> Stream results  (cost=533 rows=20) (actual time=0.342..0.921 rows=20 loops=1)
        -> Nested loop inner join  (cost=533 rows=20) (actual time=0.336..0.903 rows=20 loops=1)
            -> Table scan on page_ids  (cost=451..453 rows=20) (actual time=0.042..0.0449 rows=20 loops=1)
                -> Materialize  (cost=451..451 rows=20) (actual time=0.0413..0.0413 rows=20 loops=1)
                    -> Limit/Offset: 20/100 row(s)  (cost=449 rows=20) (actual time=0.0305..0.0321 rows=20 loops=1)
                        -> Covering index lookup on i using ix_tracks_status_created_at (status='PUBLISHED')  (cost=449 rows=4000) (actual time=0.014..0.0278 rows=120 loops=1)
            -> Single-row index lookup on t using PRIMARY (id=page_ids.id)  (cost=0.646 rows=1) (actual time=0.0427..0.0427 rows=1 loops=20)

```


## 实现决策与解释

- 原 V7 直接宽行投影：created_at 无索引，初页即出现全表 filesort；全状态 title/artist_name 在深 offset 下也可能转成全表排序。
- V8 增加 ix_tracks_created_at、ix_tracks_status_title、ix_tracks_status_artist_name、ix_tracks_status_created_at；原有索引不删除。InnoDB 辅助索引含主键，可满足同向 ID 次排序。
- 内容查询同时改为派生表 ID 分页再回表。本报告前后包含查询形态和索引两项变化，不能把全部收益单独归因于建索引。
- 索引 ID 查询的 EXPLAIN 使用 covering index。外层 Using temporary / Using filesort 仅针对 LIMIT 后本页，不是对整个业务表排序；传统 EXPLAIN 的 rows 是估计值，不能直接当作实际派生表行数。
- EXPLAIN ANALYZE 的全部状态 title/createdAt，offset=10000、size=20：覆盖索引扫描实际 10020 项，派生表实际 20 行、回表 20 次、外层排序 20 行。单状态 offset=100 实际扫描 120 项后处理 20 行。
- COUNT 依然统计全部匹配记录，选择覆盖索引不代表恒定时间。分页上限仅限制 offset，不限制精确计数的总工作量。
- 四个新增索引增加存储及写入维护成本；迁移部署需要评估可用空间与元数据锁影响。未强制索引、未承诺测试环境以外的时延 SLA。
- 本资料由真实 MySQL 8.4 产生；具体成本、估计行数和毫秒数可能随统计信息、硬件、版本变化。测试保留真实计划，不靠锁死这些数值或 mock 制造性能结论。
