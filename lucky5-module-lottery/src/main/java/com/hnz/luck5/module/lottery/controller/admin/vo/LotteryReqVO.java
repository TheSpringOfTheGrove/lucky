package com.hnz.luck5.module.lottery.controller.admin.vo;

import com.hnz.luck5.framework.common.pojo.PageParam;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

public final class LotteryReqVO {

    private LotteryReqVO() {
    }

    @Data
    public static class BooleanValue {
        @NotNull
        private Boolean value;
    }

    @Data
    public static class Room {
        @NotNull
        private Boolean open;
    }

    @Data
    public static class MessagePage extends PageParam {
        @Size(max = 100)
        private String period;
        @Size(max = 500)
        private String content;
        @Size(max = 100)
        private String nickname;
    }

    @Data
    public static class Config {
        private String url;
        private String account;
        private String password;
        @DecimalMin("0")
        private BigDecimal alertValue;
        @NotNull
        private Boolean bossMode;
        @Min(0)
        @Max(2)
        private Integer playType;
        @NotNull
        private Boolean useProxy;
    }

    @Data
    public static class LinkConfig {
        @NotNull
        private Boolean groupLinkEnabled;
        @NotNull
        private Boolean privateLinkEnabled;
        @NotBlank
        @Pattern(regexp = "GROUP|PRIVATE")
        private String defaultRoomMode;
    }

    @Data
    public static class ChimaConfig {
        private BigDecimal siZiXian;
        private BigDecimal sanZiXian;
        private BigDecimal erZiXian;
        private BigDecimal danZiXian;
        private BigDecimal siDingWei;
        private BigDecimal sanDingWei;
        private BigDecimal erDingWei;
        private BigDecimal yiDingWei;
        private BigDecimal yinKuiMax;
        private BigDecimal yinKuiMin;
    }

    @Data
    public static class Integration {
        @Size(max = 100)
        private String account;
        @Size(max = 100)
        private String group;
    }

    @Data
    public static class Member {
        private String id;
        @NotBlank
        @Size(max = 100)
        private String name;
        @DecimalMin("0")
        private BigDecimal balance;
        private String status;
        private String partner;
        @DecimalMin("0")
        private BigDecimal normalRate;
        @DecimalMin("0")
        private BigDecimal lhhRate;
        @DecimalMin("0")
        private BigDecimal partnerNormalRate;
        @DecimalMin("0")
        private BigDecimal partnerLhhRate;
        private String tag;
        private String externalNickname;
        private BigDecimal totalBet;
        private BigDecimal profitLoss;
        private String memberType;
        private Boolean autoProxy;
        private Boolean autoBetEnabled;
        @DecimalMin("0.01")
        private BigDecimal autoTopUpAmount;
        private Boolean eatEnabled;
        private Boolean searchable;
        private String fingerprint;
        private Boolean privateChat;
        private Boolean webOnly;
        private String blueWhalePassword;
    }

    @Data
    public static class Transfer {
        @NotBlank
        private String type;
        @NotNull
        @DecimalMin("0.01")
        private BigDecimal amount;
        @Size(max = 200)
        private String remark;
    }

    @Data
    public static class Audit {
        @NotBlank
        private String status;
        @Size(max = 200)
        private String remark;
    }

    @Data
    public static class Settle {
        private String result;
        @Size(max = 500)
        private String reason;
        private String bigSmall;
        private String oddEven;
        private String dragonTiger;
    }

    @Data
    public static class Odd {
        @NotBlank
        private String id;
        private String play;
        private String item;
        @NotNull
        @DecimalMin("0")
        private BigDecimal rate;
        private BigDecimal secondaryRate;
        private BigDecimal minLimit;
        private BigDecimal maxLimit;
        private String status;
    }

    @Data
    public static class Odds {
        @Valid
        @NotEmpty
        private List<Odd> odds;
    }

    @Data
    public static class PresetOrder {
        private String id;
        private String member;
        @NotBlank
        @Size(max = 2000)
        private String content;
    }

    @Data
    public static class QuickCommand {
        private String id;
        @NotBlank
        @Size(max = 100)
        private String label;
        @NotBlank
        @Size(max = 1000)
        private String content;
        @NotNull
        @Min(0)
        private Integer sort;
        @NotNull
        private Boolean enabled;
    }

    @Data
    public static class FollowOrder {
        private String id;
        @NotBlank
        @Size(max = 100)
        private String source;
        @NotBlank
        @Size(max = 2000)
        private String target;
        @NotNull
        @DecimalMin("0.1")
        private BigDecimal ratio;
        @NotNull
        private Boolean enabled;
    }

    @Data
    public static class MessageStatus {
        @NotBlank
        private String status;
    }

    @Data
    public static class PlaceBet {
        private String memberId;
        private String memberName;
        @NotBlank
        @Size(max = 40)
        private String period;
        @NotBlank
        @Size(max = 2000)
        private String content;
        @NotBlank
        private String channel;
        @Size(max = 100)
        private String externalId;
    }

    @Data
    public static class IncomingMessage {
        private String memberId;
        private String memberName;
        @Size(max = 40)
        private String period;
        @NotBlank
        @Size(max = 2000)
        private String content;
        @NotBlank
        private String channel;
        @Size(max = 100)
        private String externalId;
    }

    @Data
    public static class Password {
        @NotBlank
        private String password;
    }

    @Data
    public static class DiscountItem {
        @NotBlank
        private String id;
        @DecimalMin("0")
        private BigDecimal normalRate;
        @DecimalMin("0")
        private BigDecimal lhhRate;
        @Size(max = 100)
        private String partner;
        @DecimalMin("0")
        private BigDecimal partnerNormalRate;
        @DecimalMin("0")
        private BigDecimal partnerLhhRate;
        private Boolean puller;
    }

    @Data
    public static class Discounts {
        @Valid
        @NotEmpty
        private List<DiscountItem> members;
    }
}
