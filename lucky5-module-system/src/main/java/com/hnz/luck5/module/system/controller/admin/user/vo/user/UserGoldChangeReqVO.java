package com.hnz.luck5.module.system.controller.admin.user.vo.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class UserGoldChangeReqVO {
    @Schema(description = "目标代理或老板用户 ID")
    @NotNull
    private Long userId;

    @Schema(description = "正整数金币数，1 金币 = 24 小时")
    @NotNull
    @Positive
    private Long amount;

    @Schema(description = "客户端生成的一次性操作号；重试须保持不变")
    @NotBlank
    private String operationKey;
}
