package com.hnz.luck5.module.lottery.dal.mysql;

import com.hnz.luck5.framework.mybatis.core.mapper.BaseMapperX;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IssueMapper extends BaseMapperX<IssueDO> {

    /**
     * Recover one verified, formally opened historical period only when every order is local and
     * still safe for the normal idempotent settlement path. Live periods are selected first.
     */
    @Select("""
            SELECT i.* FROM lucky5_issue i
            JOIN lucky5_draw d ON d.tenant_id = i.tenant_id AND d.user_id = i.user_id
                AND d.period = i.period AND d.deleted = b'0'
            WHERE i.tenant_id = #{tenantId} AND i.user_id = #{userId} AND i.deleted = b'0'
                AND i.status = 'DRAWN' AND i.opened_at IS NOT NULL
                AND i.draw_confirmations >= 2 AND i.result REGEXP '^[0-9]{5}$'
                AND i.result <> '00000' AND d.status = '待结算'
                AND REPLACE(d.result, ',', '') = i.result
                AND NOT EXISTS (
                    SELECT 1 FROM lucky5_order o
                    WHERE o.tenant_id = i.tenant_id AND o.user_id = i.user_id AND o.period = i.period
                        AND (o.deleted <> b'0' OR o.delivery_mode <> 'LOCAL_ONLY'
                            OR o.market_status <> 'NOT_REQUIRED'
                            OR o.status NOT IN ('未开奖', '已退码'))
                )
            ORDER BY i.period ASC LIMIT 1
            """)
    IssueDO selectRecoverableHistoricalDraw(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
}

