package com.hnz.luck5.module.system.dal.mysql.user;

import com.hnz.luck5.framework.common.pojo.PageResult;
import com.hnz.luck5.framework.mybatis.core.mapper.BaseMapperX;
import com.hnz.luck5.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.hnz.luck5.module.system.controller.admin.user.vo.user.UserPageReqVO;
import com.hnz.luck5.module.system.dal.dataobject.user.AdminUserDO;
import com.hnz.luck5.module.system.dal.dataobject.user.UserOwnerCountDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AdminUserMapper extends BaseMapperX<AdminUserDO> {

    String MANAGEMENT_ROLE_EXISTS = "SELECT 1 FROM system_user_role ur " +
            "JOIN system_role r ON r.id = ur.role_id AND r.deleted = FALSE AND r.status = 0 " +
            "WHERE ur.user_id = system_users.id AND ur.deleted = FALSE " +
            "AND r.tenant_id = system_users.tenant_id AND r.code IN ('super_admin', 'lottery_agent')";

    default AdminUserDO selectByUsername(String username) {
        return selectOne(AdminUserDO::getUsername, username);
    }

    default AdminUserDO selectByEmail(String email) {
        return selectOne(AdminUserDO::getEmail, email);
    }

    default AdminUserDO selectByMobile(String mobile) {
        return selectOne(AdminUserDO::getMobile, mobile);
    }

    default PageResult<AdminUserDO> selectPage(UserPageReqVO reqVO, Collection<Long> deptIds, Collection<Long> userIds,
                                               Long parentAgentUserId) {
        return selectPage(reqVO, pageQuery(reqVO, deptIds, userIds)
                .eqIfPresent(AdminUserDO::getParentAgentUserId, parentAgentUserId)
                .orderByDesc(AdminUserDO::getId));
    }

    default PageResult<AdminUserDO> selectManagementPage(UserPageReqVO reqVO, Collection<Long> deptIds,
                                                         Collection<Long> userIds) {
        return selectPage(reqVO, pageQuery(reqVO, deptIds, userIds)
                .exists(MANAGEMENT_ROLE_EXISTS).orderByDesc(AdminUserDO::getId));
    }

    default PageResult<AdminUserDO> selectDirectOwnerPage(UserPageReqVO reqVO, Long parentUserId) {
        return selectPage(reqVO, pageQuery(reqVO, null, null)
                .eq(AdminUserDO::getAccountType, "OWNER")
                .eq(AdminUserDO::getParentAgentUserId, parentUserId)
                .orderByDesc(AdminUserDO::getId));
    }

    private LambdaQueryWrapperX<AdminUserDO> pageQuery(UserPageReqVO reqVO, Collection<Long> deptIds,
                                                        Collection<Long> userIds) {
        return new LambdaQueryWrapperX<AdminUserDO>()
                .likeIfPresent(AdminUserDO::getUsername, reqVO.getUsername())
                .likeIfPresent(AdminUserDO::getMobile, reqVO.getMobile())
                .eqIfPresent(AdminUserDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(AdminUserDO::getCreateTime, reqVO.getCreateTime())
                .inIfPresent(AdminUserDO::getDeptId, deptIds)
                .inIfPresent(AdminUserDO::getId, userIds);
    }

    @Select("<script>SELECT parent_agent_user_id AS parentUserId, COUNT(*) AS ownerCount " +
            "FROM system_users WHERE tenant_id = #{tenantId} AND deleted = FALSE " +
            "AND account_type = 'OWNER' AND parent_agent_user_id IN " +
            "<foreach collection='parentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY parent_agent_user_id</script>")
    List<UserOwnerCountDO> selectOwnerCounts(@Param("tenantId") Long tenantId,
                                             @Param("parentIds") Collection<Long> parentIds);

    @Select("SELECT * FROM system_users WHERE id = #{userId} AND tenant_id = #{tenantId} AND deleted = FALSE FOR UPDATE")
    AdminUserDO selectForUpdate(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    default List<AdminUserDO> selectListByNickname(String nickname) {
        return selectList(new LambdaQueryWrapperX<AdminUserDO>().like(AdminUserDO::getNickname, nickname));
    }

    default List<AdminUserDO> selectListByStatus(Integer status) {
        return selectList(AdminUserDO::getStatus, status);
    }

    default List<AdminUserDO> selectListByDeptIds(Collection<Long> deptIds) {
        return selectList(AdminUserDO::getDeptId, deptIds);
    }

}
