package com.hnz.luck5.module.system.api.message.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理后台用户创建消息。
 */
@Data
public class AdminUserCreatedMessage {

    @NotNull(message = "租户编号不能为空")
    private Long tenantId;

    @NotNull(message = "用户编号不能为空")
    private Long userId;

    @NotBlank(message = "用户名不能为空")
    private String username;

}
