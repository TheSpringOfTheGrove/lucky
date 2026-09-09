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
    // Do not rely on Connector/J rewriting addBatch() calls: this is not consistently enabled by every
    // datasource configuration. These are explicit multi-value INSERT statement sizes and are safely below
    // MySQL's 64 MiB default packet limit for our fixed-size bet item rows.
    private static final int BET_ITEM_MULTI_VALUE_BATCH_SIZE = 20_000;
    private static final int MARKET_ROUTE_MULTI_VALUE_BATCH_SIZE = 5_000;
    private static final int SLOW_LOG_ITEM_THRESHOLD = 1_000;
    private static final String BET_ITEM_INSERT_PREFIX = """
            INSERT INTO lucky5_bet_item
                (id, user_id, order_id, play, selection, amount, odds, won, payout, tenant_id)
            VALUES
            """;
    private static final String BET_ITEM_VALUES = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String MARKET_ROUTE_INSERT_PREFIX = """
            INSERT INTO lucky5_market_route_item
                (id, user_id, order_id, bet_item_id, period, play, selection, route_type,
                 local_amount, market_amount, odds, local_payout, market_guid, market_bet_id,
                 market_serial_no, market_bet_count, market_odds, status, attempts, last_error, tenant_id)
            VALUES
            """;
    private static final String MARKET_ROUTE_VALUES = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @Resource
    private JdbcTemplate jdbcTemplate;

    public void insertBetItems(Long tenantId, List<BetItemDO> items) {
        if (items.isEmpty()) return;
        long startedAt = System.nanoTime();
        forEachChunk(items.size(), BET_ITEM_MULTI_VALUE_BATCH_SIZE, (from, to) -> jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(multiValueSql(BET_ITEM_INSERT_PREFIX, BET_ITEM_VALUES, to - from));
            int parameterIndex = 1;
            for (int index = from; index < to; index++) {
                BetItemDO item = items.get(index);
                statement.setString(parameterIndex++, item.getId());
                statement.setLong(parameterIndex++, item.getUserId());
                statement.setString(parameterIndex++, item.getOrderId());
                statement.setString(parameterIndex++, item.getPlay());
                statement.setString(parameterIndex++, item.getSelection());
                statement.setBigDecimal(parameterIndex++, item.getAmount());
                statement.setBigDecimal(parameterIndex++, item.getOdds());
                if (item.getWon() == null) statement.setNull(parameterIndex++, Types.BIT);
                else statement.setBoolean(parameterIndex++, item.getWon());
                statement.setBigDecimal(parameterIndex++, item.getPayout());
                statement.setLong(parameterIndex++, tenantId);
            }
            return statement;
        }));
        logBatchElapsed("bet-item", items.size(), startedAt);
    }

    public void insertMarketRoutes(Long tenantId, List<MarketRouteItemDO> items) {
        if (items.isEmpty()) return;
        long startedAt = System.nanoTime();
        forEachChunk(items.size(), MARKET_ROUTE_MULTI_VALUE_BATCH_SIZE, (from, to) -> jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(multiValueSql(MARKET_ROUTE_INSERT_PREFIX,
                    MARKET_ROUTE_VALUES, to - from));
            int parameterIndex = 1;
            for (int index = from; index < to; index++) {
                MarketRouteItemDO item = items.get(index);
                statement.setString(parameterIndex++, item.getId());
                statement.setLong(parameterIndex++, item.getUserId());
                statement.setString(parameterIndex++, item.getOrderId());
                statement.setString(parameterIndex++, item.getBetItemId());
                statement.setString(parameterIndex++, item.getPeriod());
                statement.setString(parameterIndex++, item.getPlay());
                statement.setString(parameterIndex++, item.getSelection());
                statement.setString(parameterIndex++, item.getRouteType());
                statement.setBigDecimal(parameterIndex++, item.getLocalAmount());
                statement.setBigDecimal(parameterIndex++, item.getMarketAmount());
                statement.setBigDecimal(parameterIndex++, item.getOdds());
                statement.setBigDecimal(parameterIndex++, item.getLocalPayout());
                statement.setString(parameterIndex++, item.getMarketGuid());
                statement.setString(parameterIndex++, item.getMarketBetId());
                statement.setString(parameterIndex++, item.getMarketSerialNo());
                statement.setInt(parameterIndex++, item.getMarketBetCount());
                statement.setBigDecimal(parameterIndex++, item.getMarketOdds());
                statement.setString(parameterIndex++, item.getStatus());
                statement.setInt(parameterIndex++, item.getAttempts());
                statement.setString(parameterIndex++, item.getLastError());
                statement.setLong(parameterIndex++, tenantId);
            }
            return statement;
        }));
        logBatchElapsed("market-route", items.size(), startedAt);
    }

    private void forEachChunk(int size, int chunkSize, ChunkConsumer consumer) {
        for (int from = 0; from < size; from += chunkSize) {
            consumer.accept(from, Math.min(from + chunkSize, size));
        }
    }

    private String multiValueSql(String prefix, String rowValues, int rows) {
        StringBuilder sql = new StringBuilder(prefix.length() + (rowValues.length() + 1) * rows);
        sql.append(prefix);
        for (int index = 0; index < rows; index++) {
            if (index > 0) sql.append(',');
            sql.append(rowValues);
        }
        return sql.toString();
    }

    @FunctionalInterface
    private interface ChunkConsumer {
        void accept(int from, int to);
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
