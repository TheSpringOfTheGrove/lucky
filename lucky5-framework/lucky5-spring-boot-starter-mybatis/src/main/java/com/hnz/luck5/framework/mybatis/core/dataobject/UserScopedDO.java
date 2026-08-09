package com.hnz.luck5.framework.mybatis.core.dataobject;

/**
 * 需要按后台用户隔离的数据对象。
 */
public interface UserScopedDO {

    Long getUserId();

    UserScopedDO setUserId(Long userId);

}
