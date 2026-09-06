-- 已经导入过 hmdp.sql 的数据库需要手动执行本增量脚本。
-- 执行前先排查并清理重复的 (voucher_id, user_id) 数据，否则 ALTER 会失败。
ALTER TABLE `tb_voucher_order`
    ADD UNIQUE INDEX `uk_voucher_user` (`voucher_id`, `user_id`) USING BTREE;
