package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.BetItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Types;
import java.util.List;

/**
 * Executes high-volume lottery detail inserts on the current Spring transaction connection.
 * Tenant identifiers are explicit because these statements intentionally bypass ORM SQL rewriting.
 */
@Service
public class LotteryBatchInsertService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryBatchInsertService.class);
    private static final int BATCH_SIZE = 1_000;
    private static final int SLOW_LOG_ITEM_THRESHOLD = 1_000;
    private static final String BET_ITEM_INSERT = """
            INSERT INTO lucky5_bet_item
                (id, user_id, order_id, play, selection, amount, odds, won, payout, tenant_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String MARKET_ROUTE_INSERT = """
            INSERT INTO lucky5_market_route_item
                (id, user_id, order_id, bet_item_id, period, play, selection, route_type,
                 local_amount, market_amount, odds, local_payout, market_guid, market_bet_id,
                 market_serial_no, market_bet_count, market_odds, status, attempts, last_error, tenant_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    public void insertBetItems(Long tenantId, List<BetItemDO> items) {
        if (items.isEmpty()) return;
        long startedAt = System.nanoTime();
        jdbcTemplate.batchUpdate(BET_ITEM_INSERT, items, BATCH_SIZE, (statement, item) -> {
            statement.setString(1, item.getId());
            statement.setLong(2, item.getUserId());
            statement.setString(3, item.getOrderId());
            statement.setString(4, item.getPlay());
            statement.setString(5, item.getSelection());
            statement.setBigDecimal(6, item.getAmount());
            statement.setBigDecimal(7, item.getOdds());
            if (item.getWon() == null) statement.setNull(8, Types.BIT);
            else statement.setBoolean(8, item.getWon());
            statement.setBigDecimal(9, item.getPayout());
            statement.setLong(10, tenantId);
        });
        logBatchElapsed("bet-item", items.size(), startedAt);
    }

    public void insertMarketRoutes(Long tenantId, List<MarketRouteItemDO> items) {
        if (items.isEmpty()) return;
        long startedAt = System.nanoTime();
        jdbcTemplate.batchUpdate(MARKET_ROUTE_INSERT, items, BATCH_SIZE, (statement, item) -> {
            statement.setString(1, item.getId());
            statement.setLong(2, item.getUserId());
            statement.setString(3, item.getOrderId());
            statement.setString(4, item.getBetItemId());
            statement.setString(5, item.getPeriod());
            statement.setString(6, item.getPlay());
            statement.setString(7, item.getSelection());
            statement.setString(8, item.getRouteType());
            statement.setBigDecimal(9, item.getLocalAmount());
            statement.setBigDecimal(10, item.getMarketAmount());
            statement.setBigDecimal(11, item.getOdds());
            statement.setBigDecimal(12, item.getLocalPayout());
            statement.setString(13, item.getMarketGuid());
            statement.setString(14, item.getMarketBetId());
            statement.setString(15, item.getMarketSerialNo());
            statement.setInt(16, item.getMarketBetCount());
            statement.setBigDecimal(17, item.getMarketOdds());
            statement.setString(18, item.getStatus());
            statement.setInt(19, item.getAttempts());
            statement.setString(20, item.getLastError());
            statement.setLong(21, tenantId);
        });
        logBatchElapsed("market-route", items.size(), startedAt);
    }

    private void logBatchElapsed(String type, int itemCount, long startedAt) {
        if (itemCount < SLOW_LOG_ITEM_THRESHOLD) return;
        LOGGER.info("Lucky5 detail batch insert completed: type={}, itemCount={}, elapsedMs={}",
                type, itemCount, elapsedMillis(startedAt));
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
