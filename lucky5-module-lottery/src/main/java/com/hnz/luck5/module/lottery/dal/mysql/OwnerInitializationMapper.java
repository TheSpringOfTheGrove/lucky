package com.hnz.luck5.module.lottery.dal.mysql;

import com.hnz.luck5.framework.mybatis.core.mapper.BaseMapperX;
import com.hnz.luck5.module.lottery.dal.dataobject.OwnerInitializationDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OwnerInitializationMapper extends BaseMapperX<OwnerInitializationDO> {

    @Insert("""
            INSERT IGNORE INTO lucky5_owner_initialization
                (tenant_id, user_id, first_source, last_source, initialization_count, schema_version,
                 first_initialized_at, last_initialized_at, last_operator_user_id,
                 creator, updater, create_time, update_time, deleted)
            VALUES
                (#{tenantId}, #{userId}, #{source}, #{source}, 1, #{schemaVersion},
                 NOW(6), NOW(6), #{operatorUserId},
                 CAST(#{operatorUserId} AS CHAR), CAST(#{operatorUserId} AS CHAR), NOW(6), NOW(6), b'0')
            """)
    int insertIfAbsent(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                       @Param("source") String source, @Param("operatorUserId") Long operatorUserId,
                       @Param("schemaVersion") Integer schemaVersion);

}
