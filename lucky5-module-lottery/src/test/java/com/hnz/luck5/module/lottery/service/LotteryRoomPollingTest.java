package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hnz.luck5.framework.tenant.core.context.TenantContextHolder;
import com.hnz.luck5.framework.tenant.core.util.TenantUtils;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.LotteryConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.SystemStateDO;
import com.hnz.luck5.module.lottery.dal.mysql.AmountRecordMapper;
import com.hnz.luck5.module.lottery.dal.mysql.DrawMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IssueTransitionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.LotteryConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import com.hnz.luck5.module.lottery.dal.mysql.QuickCommandMapper;
import com.hnz.luck5.module.lottery.dal.mysql.SwitchSettingMapper;
import com.hnz.luck5.module.lottery.dal.mysql.SystemStateMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LotteryRoomPollingTest {

    private final LotteryServiceImpl service = new LotteryServiceImpl();
    private final AtomicLong ticker = new AtomicLong();
    private final AtomicReference<String> ownReply = new AtomicReference<>("旧回执");
    private final AtomicReference<IssueDO> currentIssue = new AtomicReference<>();
    private final AtomicLong groupQueries = new AtomicLong();
    private MemberMapper members;
    private OrderMapper orders;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant tables = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(tables, MemberDO.class);
        TableInfoHelper.initTableInfo(tables, MessageDO.class);
        ReflectionTestUtils.setField(service, "roomOwnerSnapshots", Caffeine.newBuilder()
                .ticker(ticker::get).expireAfterWrite(Duration.ofMillis(500)).build());
        ReflectionTestUtils.setField(service, "roomGroupTimelines", Caffeine.newBuilder()
                .ticker(ticker::get).expireAfterWrite(Duration.ofMillis(500)).build());

        LotteryConfigMapper configs = mapper("lotteryConfigMapper", LotteryConfigMapper.class);
        when(configs.selectOne(any())).thenReturn(new LotteryConfigDO());
        SystemStateMapper states = mapper("systemStateMapper", SystemStateMapper.class);
        when(states.selectOne(any())).thenReturn(new SystemStateDO());
        IssueMapper issues = mapper("issueMapper", IssueMapper.class);
        when(issues.selectOne(any())).thenAnswer(call -> currentIssue.get());
        members = mapper("memberMapper", MemberMapper.class);
        orders = mapper("orderMapper", OrderMapper.class);
        mapper("amountRecordMapper", AmountRecordMapper.class);
        mapper("drawMapper", DrawMapper.class);
        mapper("issueTransitionMapper", IssueTransitionMapper.class);
        mapper("quickCommandMapper", QuickCommandMapper.class);
        mapper("switchSettingMapper", SwitchSettingMapper.class);
        ReflectionTestUtils.setField(service, "issueFreshnessPolicy", new LotteryIssueFreshnessPolicy());
        ReflectionTestUtils.setField(service, "drawVerificationService", new LotteryDrawVerificationService());
        ReflectionTestUtils.setField(service, "robotReplyTemplate", new LotteryRobotReplyTemplate());

        MessageMapper messages = mapper("messageMapper", MessageMapper.class);
        when(messages.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<MessageDO> query = call.getArgument(0);
            String sql = query.getSqlSegment();
            var params = query.getParamNameValuePairs().values();
            if (!params.contains("网页群")) return List.of();
            if (sql.contains("member_id =")) {
                return params.contains("alice") ? List.of(message(1L, ownReply.get())) : List.of();
            }
            if (sql.contains("member_id IS NULL")) return List.of();
            groupQueries.incrementAndGet();
            return List.of(message(1L, "公共回执-" + TenantContextHolder.getRequiredTenantId()
                    + "-" + (params.contains(2L) ? 2 : 1)));
        });
    }

    @Test
    void sharesGroupReadsButKeepsPersonalReceiptAndBalanceFresh() {
        assertThat(firstReply(session(1L, "alice", 10, "GROUP"))).isEqualTo("旧回执");

        ownReply.set("新回执");
        Map<String, Object> alice = session(1L, "alice", 9, "GROUP");
        assertThat(firstReply(alice)).isEqualTo("新回执");
        assertThat(((Map<?, ?>) alice.get("member")).get("balance")).isEqualTo(new BigDecimal("9.00"));

        Map<String, Object> bob = session(1L, "bob", 5, "GROUP");
        assertThat(firstMessage(bob).get("content")).isEqualTo("公共回执-1-1");
        assertThat(firstMessage(bob).get("own")).isEqualTo(false);
        assertThat(groupQueries.get()).isEqualTo(1L);
        verify(members, times(1)).selectList(any());
        verify(orders, times(3)).selectList(any());

        assertThat(session(1L, "bob", 5, "PRIVATE").get("messages")).isEqualTo(List.of());
        assertThat(firstMessage(session(2L, "bob", 5, "GROUP")).get("content")).isEqualTo("公共回执-2-1");
        assertThat(firstMessage(session(1L, 2L, "bob", 5, "GROUP")).get("content")).isEqualTo("公共回执-1-2");
        assertThat(groupQueries.get()).isEqualTo(3L);
    }

    @Test
    void refreshesSharedDataOnPhaseChangeAndAfterFiveHundredMilliseconds() {
        IssueDO issue = new IssueDO();
        issue.setPeriod("20261123111");
        issue.setStatus("OPEN");
        currentIssue.set(issue);
        session(1L, "bob", 5, "GROUP");

        issue.setOpenedAt(LocalDateTime.now());
        session(1L, "bob", 5, "GROUP");
        assertThat(groupQueries.get()).isEqualTo(2L);

        ticker.set(Duration.ofMillis(501).toNanos());
        session(1L, "bob", 5, "GROUP");
        assertThat(groupQueries.get()).isEqualTo(3L);
    }

    private <T> T mapper(String field, Class<T> type) {
        T mock = mock(type);
        ReflectionTestUtils.setField(service, field, mock);
        return mock;
    }

    private Map<String, Object> session(Long tenantId, String memberId, int balance, String mode) {
        return session(tenantId, 1L, memberId, balance, mode);
    }

    private Map<String, Object> session(Long tenantId, Long ownerId, String memberId, int balance, String mode) {
        MemberDO member = new MemberDO();
        member.setId(memberId);
        member.setName(memberId);
        member.setBalance(BigDecimal.valueOf(balance));
        member.setUserId(ownerId);
        return TenantUtils.execute(tenantId, () -> ReflectionTestUtils.invokeMethod(service,
                "getRoomSessionInternal", member, mode));
    }

    private static Map<?, ?> firstMessage(Map<String, Object> session) {
        return (Map<?, ?>) ((List<?>) session.get("messages")).get(0);
    }

    private static Object firstReply(Map<String, Object> session) {
        return firstMessage(session).get("reply");
    }

    private static MessageDO message(Long id, String reply) {
        MessageDO result = new MessageDO();
        result.setId(id);
        result.setChannel("网页群");
        result.setCommandType("CHAT");
        result.setMemberId("alice");
        result.setMember("alice");
        result.setContent(reply);
        result.setReply(reply);
        result.setCreateTime(LocalDateTime.of(2026, 11, 23, 18, 0));
        return result;
    }
}
