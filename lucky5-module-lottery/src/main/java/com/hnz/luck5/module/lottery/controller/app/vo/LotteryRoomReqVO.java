package com.hnz.luck5.module.lottery.controller.app.vo;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

public final class LotteryRoomReqVO {

    private LotteryRoomReqVO() {
    }

    @Data
    public static class Credential {
        @NotNull
        private Long tenantId;
        @NotBlank
        @Size(max = 100)
        private String openId;
        private String fp;
    }

    @Data
    public static class Bet extends Credential {
        @NotBlank
        @Size(max = 40)
        private String period;
        @NotBlank
        @Size(max = 2000)
        private String content;
        @Size(max = 100)
        private String externalId;
    }

    @Data
    public static class Message extends Credential {
        @Size(max = 40)
        private String period;
        @NotBlank
        @Size(max = 2000)
        private String content;
        @Size(max = 100)
        private String externalId;
    }

    @Data
    public static class Amount extends Credential {
        @NotBlank
        private String type;
        @NotNull
        @DecimalMin("0.01")
        private BigDecimal amount;
        @Size(max = 200)
        private String remark;
    }
}
