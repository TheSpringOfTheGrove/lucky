package com.hnz.luck5.module.system.util.user;

import java.time.LocalDateTime;

/** 后台账号期限统一判定；空值仅兼容迁移前的数据。 */
public final class AdminUserExpiration {

    private AdminUserExpiration() {
    }

    public static boolean isExpired(LocalDateTime expireTime, LocalDateTime now) {
        return expireTime != null && !now.isBefore(expireTime);
    }
}
