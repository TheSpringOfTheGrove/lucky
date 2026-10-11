package com.hnz.luck5.module.system.dal.mysql.user;

import com.hnz.luck5.framework.mybatis.core.mapper.BaseMapperX;
import com.hnz.luck5.module.system.dal.dataobject.user.GoldLedgerDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GoldLedgerMapper extends BaseMapperX<GoldLedgerDO> {

    default GoldLedgerDO selectByOperationKey(String operationKey) {
        return selectOne(GoldLedgerDO::getOperationKey, operationKey);
    }

    @Select("SELECT COALESCE(SUM(amount), 0) FROM system_gold_ledger " +
            "WHERE tenant_id = #{tenantId} AND deleted = FALSE AND operation_type = #{operationType} " +
            "AND (#{sourceUserId} IS NULL OR source_user_id = #{sourceUserId})")
    Long sumAmount(@Param("tenantId") Long tenantId, @Param("operationType") String operationType,
                   @Param("sourceUserId") Long sourceUserId);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM system_gold_ledger " +
            "WHERE tenant_id = #{tenantId} AND deleted = FALSE AND operation_type = 'OWNER_ALLOCATION' " +
            "AND source_user_id = #{sourceUserId} AND target_user_id = #{targetUserId}")
    Long sumOwnerAllocation(@Param("tenantId") Long tenantId, @Param("sourceUserId") Long sourceUserId,
                            @Param("targetUserId") Long targetUserId);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM system_gold_ledger " +
            "WHERE tenant_id = #{tenantId} AND deleted = FALSE AND operation_type = 'OWNER_ALLOCATION' " +
            "AND source_user_id IS NULL AND target_user_id = #{targetUserId}")
    Long sumAdminOwnerAllocation(@Param("tenantId") Long tenantId, @Param("targetUserId") Long targetUserId);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM system_gold_ledger " +
            "WHERE tenant_id = #{tenantId} AND deleted = FALSE AND " +
            "(operation_type = 'ADMIN_GRANT' OR (operation_type = 'OWNER_ALLOCATION' AND source_user_id IS NULL))")
    Long sumAdminRecharge(@Param("tenantId") Long tenantId);
}
