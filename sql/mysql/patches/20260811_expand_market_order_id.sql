-- 外盘一笔玩家订单可能拆成大量注单，每注都有独立的 18 位流水号。
-- MEDIUMTEXT 可容纳完整流水号集合，避免多注确认成功后因主订单字段溢出而回滚。
ALTER TABLE `lucky5_order`
    MODIFY COLUMN `market_order_id` MEDIUMTEXT NOT NULL;
