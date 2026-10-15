package com.hnz.luck5.module.lottery.dal.mysql;

import com.hnz.luck5.framework.mybatis.core.mapper.BaseMapperX;
import com.hnz.luck5.module.lottery.dal.dataobject.PlayerFollowExecutionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface PlayerFollowExecutionMapper extends BaseMapperX<PlayerFollowExecutionDO> {

    @Select("""
            SELECT DISTINCT f.tenant_id AS tenantId, f.user_id AS userId, i.period AS period
            FROM lucky5_player_follow f
            JOIN lucky5_issue i ON i.tenant_id = f.tenant_id AND i.user_id = f.user_id
                AND i.status = 'OPEN' AND i.opened_at IS NOT NULL AND i.deleted = b'0'
            WHERE f.enabled = b'1' AND f.deleted = b'0'
                AND (CHAR_LENGTH(i.period) > CHAR_LENGTH(f.source_period)
                    OR (CHAR_LENGTH(i.period) = CHAR_LENGTH(f.source_period) AND i.period > f.source_period))
                AND NOT EXISTS (SELECT 1 FROM lucky5_player_follow_execution e
                    WHERE e.tenant_id = f.tenant_id AND e.user_id = f.user_id
                        AND e.follow_id = f.id AND e.period = i.period AND e.deleted = b'0')
            ORDER BY i.period DESC LIMIT 100
            """)
    List<Map<String, Object>> selectMissingOpenScopes();
}
