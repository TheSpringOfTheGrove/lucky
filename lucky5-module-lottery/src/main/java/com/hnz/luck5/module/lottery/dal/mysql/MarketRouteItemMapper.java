package com.hnz.luck5.module.lottery.dal.mysql;

import com.hnz.luck5.framework.mybatis.core.mapper.BaseMapperX;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface MarketRouteItemMapper extends BaseMapperX<MarketRouteItemDO> {

    @Select("""
            <script>
            SELECT r.period,
                   COALESCE(SUM(r.market_amount), 0) AS bet_money,
                   COALESCE(SUM(CASE WHEN b.won = 1 THEN r.market_amount
                       * COALESCE(NULLIF(r.market_odds, 0), NULLIF(r.odds, 0), 0) ELSE 0 END), 0) AS market_win
            FROM lucky5_market_route_item r
            JOIN lucky5_order o ON o.tenant_id = r.tenant_id AND o.user_id = r.user_id
                AND o.id = r.order_id AND o.deleted = 0
            LEFT JOIN lucky5_bet_item b ON b.tenant_id = r.tenant_id AND b.user_id = r.user_id
                AND b.id = r.bet_item_id AND b.deleted = 0
            WHERE r.tenant_id = #{tenantId} AND r.user_id = #{userId} AND r.deleted = 0
              AND r.market_amount &gt; 0
              AND (r.status IN ('CONFIRMED', 'SETTLED', 'CANCEL_PENDING', 'CANCEL_FAILED')
                   OR r.market_bet_id &lt;&gt; '')
              AND o.status IN ('已中奖', '未中奖')
              AND (o.order_type IS NULL OR o.order_type &lt;&gt; 'AUTO_PROXY')
              AND r.period IN
              <foreach collection="periods" item="period" open="(" separator="," close=")">
                  #{period}
              </foreach>
            GROUP BY r.period
            </script>
            """)
    List<Map<String, Object>> selectHistoryStatistics(@Param("tenantId") Long tenantId,
                                                       @Param("userId") Long userId,
                                                       @Param("periods") List<String> periods);

    @Select("""
            <script>
            SELECT COALESCE(SUM(r.market_amount), 0) AS bet_money,
                   COALESCE(SUM(CASE WHEN b.won = 1 THEN r.market_amount
                       * COALESCE(NULLIF(r.market_odds, 0), NULLIF(r.odds, 0), 0) ELSE 0 END), 0) AS market_win
            FROM lucky5_market_route_item r
            JOIN lucky5_order o ON o.tenant_id = r.tenant_id AND o.user_id = r.user_id
                AND o.id = r.order_id AND o.deleted = 0
            LEFT JOIN lucky5_bet_item b ON b.tenant_id = r.tenant_id AND b.user_id = r.user_id
                AND b.id = r.bet_item_id AND b.deleted = 0
            WHERE r.tenant_id = #{tenantId} AND r.user_id = #{userId} AND r.deleted = 0
              AND r.market_amount &gt; 0
              AND (r.status IN ('CONFIRMED', 'SETTLED', 'CANCEL_PENDING', 'CANCEL_FAILED')
                   OR r.market_bet_id &lt;&gt; '')
              AND o.status IN ('已中奖', '未中奖')
              AND (o.order_type IS NULL OR o.order_type &lt;&gt; 'AUTO_PROXY')
              <if test="period != null and period != ''">
                  AND o.period LIKE CONCAT('%', #{period}, '%')
              </if>
              <if test="startTime != null">AND o.create_time &gt;= #{startTime}</if>
              <if test="endTime != null">AND o.create_time &lt; #{endTime}</if>
            </script>
            """)
    Map<String, Object> selectHistorySummary(@Param("tenantId") Long tenantId,
                                              @Param("userId") Long userId,
                                              @Param("period") String period,
                                              @Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime);
}
