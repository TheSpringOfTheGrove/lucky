package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.module.lottery.dal.dataobject.DrawDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LotteryRoomDrawMessagesTest {
    private LotteryServiceImpl service;
    private MessageMapper mapper;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MessageDO.class);
        service = new LotteryServiceImpl();
        mapper = mock(MessageMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", mapper);
        ReflectionTestUtils.setField(service, "robotReplyTemplate", new LotteryRobotReplyTemplate());
    }

    @Test
    void preservesSavedDrawWhenOneHundredBetMessagesFillTheChatWindow() {
        List<MessageDO> chat = IntStream.range(1, 101).mapToObj(index -> message((long) index, "BET")).toList();
        MessageDO saved = message(0L, "DRAW_RESULT");
        saved.setDrawImage("<svg>immutable historical image</svg>");
        when(mapper.selectList(any())).thenReturn(List.of(saved));

        List<MessageDO> result = ReflectionTestUtils.invokeMethod(service, "withRecentRoomDrawMessages",
                7L, chat, List.of(draw("20260928238", true)));

        assertThat(result).hasSize(101).contains(saved).containsAll(chat);
        assertThat(saved.getDrawImage()).isEqualTo("<svg>immutable historical image</svg>");
        verify(mapper, never()).insert(any(MessageDO.class));
    }

    @Test
    void keepsAnExistingDrawOnceWithoutAnotherReadOrSyntheticMessage() {
        MessageDO saved = message(1L, "DRAW_RESULT");
        List<MessageDO> original = List.of(saved);
        List<MessageDO> result = ReflectionTestUtils.invokeMethod(service, "withRecentRoomDrawMessages",
                7L, original, List.of(draw("20260928238", true)));
        assertThat(result).isSameAs(original);
        verifyNoInteractions(mapper);
    }

    @Test
    void doesNotPublishCandidatesOrUnsettledDraws() {
        List<MessageDO> original = List.of();
        List<MessageDO> result = ReflectionTestUtils.invokeMethod(service, "withRecentRoomDrawMessages",
                7L, original, List.of(draw("20260928238", false)));
        assertThat(result).isSameAs(original);
        verifyNoInteractions(mapper);
    }

    @Test
    void missingDrawQueryIsOwnerScopedCanonicalAndBounded() {
        LambdaQueryWrapper<MessageDO> query = ReflectionTestUtils.invokeMethod(service,
                "recentRoomDrawMessageQuery", 7L, List.of("20260928238", "20260928237"));
        assertThat(query.getSqlSegment()).contains("user_id =", "command_type =", "external_id IN", "LIMIT 20");
        assertThat(query.getParamNameValuePairs().values())
                .contains(7L, "DRAW_RESULT", "draw-result:20260928238", "draw-result:20260928237");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "recentRoomDrawMessageQuery",
                7L, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "recentRoomDrawMessageQuery",
                7L, IntStream.range(0, 21).mapToObj(String::valueOf).toList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roomSessionUsesLazyImageUrlInsteadOfRepeatingTheSvg() {
        MessageDO saved = message(42L, "DRAW_REQUEST");
        saved.setDrawImage("<svg>large immutable draw snapshot</svg>");
        saved.setMemberId("member-1");
        MemberDO member = new MemberDO();
        member.setId("member-1");
        member.setName("tester");

        Map<String, Object> result = ReflectionTestUtils.invokeMethod(service, "roomMessageMap",
                saved, member, Map.of(), Map.of());

        assertThat(result.get("drawImage"))
                .isEqualTo("/app-api/lottery/room/messages/42/draw-image");
        assertThat(result.toString()).doesNotContain("large immutable draw snapshot");
    }

    @Test
    void drawImageLookupIsBoundToTheCurrentOwnerAndMessage() {
        LambdaQueryWrapper<MessageDO> query = ReflectionTestUtils.invokeMethod(service,
                "roomDrawImageQuery", 7L, 42L);

        assertThat(query.getSqlSegment()).contains("user_id =", "id =", "command_type IN");
        assertThat(query.getParamNameValuePairs().values())
                .contains(7L, 42L, "DRAW_RESULT", "DRAW_REQUEST");
    }

    @Test
    void groupHistoryIncludesAllOwnerMessagesIncludingAutomaticProxyFunding() {
        LambdaQueryWrapper<MessageDO> query = ReflectionTestUtils.invokeMethod(service,
                "sharedRoomMessageQuery", 7L, 123L);

        assertThat(query.getSqlSegment()).contains("user_id =", "channel =", "id <", "LIMIT 100")
                .doesNotContain("command_type", "message_type", "external_id");
        assertThat(query.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder(7L, "网页群", 123L);
    }

    @Test
    void anotherGroupMemberCanSeeAllPlayerRepliesIncludingTransfersAndRejections() {
        MemberDO viewer = new MemberDO();
        viewer.setId("viewer");
        viewer.setName("viewer");
        for (String command : List.of("BALANCE", "FLOW", "PROFIT_LOSS", "FLOW_REBATE", "AMOUNT_HISTORY",
                "DEPOSIT_REQUEST", "WITHDRAW_REQUEST", "AMOUNT_REJECTED", "FOLLOW", "FOLLOW_STOP",
                "CANCEL_CURRENT", "DRAW_REQUEST")) {
            MessageDO query = message(42L, command);
            query.setMemberId("sender");
            query.setMember("sender");
            query.setContent("查余额");
            query.setReply("@sender\n查询结果");

            Map<String, Object> result = ReflectionTestUtils.invokeMethod(service, "roomMessageMap",
                    query, viewer, Map.of(), Map.of());

            assertThat(result.get("own")).isEqualTo(false);
            assertThat(result.get("reply")).isEqualTo("@sender\n查询结果");
        }
        MessageDO proxyFunding = message(43L, "DEPOSIT_REQUEST");
        proxyFunding.setMemberId("proxy");
        proxyFunding.setMessageType("AUTO_PROXY");
        proxyFunding.setReply("@proxy\n上分：100\n目前：500");
        Map<String, Object> publicFunding = ReflectionTestUtils.invokeMethod(service, "roomMessageMap",
                proxyFunding, viewer, Map.of(), Map.of());
        assertThat(publicFunding).containsEntry("own", false)
                .containsEntry("reply", "@proxy\n上分：100\n目前：500");
    }

    @Test
    void publishesOneGreetingAfterTheLastDailyDraw() {
        MessageDO existing = message(60L, "DAILY_CLOSING_GREETING");
        existing.setExternalId("daily-closing-greeting:20261110060");
        when(mapper.selectOne(any())).thenReturn(null, existing);
        LocalDateTime drawTime = LocalDateTime.of(2026, 11, 10, 5, 0, 12);

        ReflectionTestUtils.invokeMethod(service, "publishDailyClosingGreetingIfMissing",
                7L, "20261110060", drawTime);
        ReflectionTestUtils.invokeMethod(service, "publishDailyClosingGreetingIfMissing",
                7L, "20261110060", drawTime);
        ReflectionTestUtils.invokeMethod(service, "publishDailyClosingGreetingIfMissing",
                7L, "20261110059", drawTime);

        ArgumentCaptor<MessageDO> capture = ArgumentCaptor.forClass(MessageDO.class);
        verify(mapper, times(1)).insert(capture.capture());
        MessageDO greeting = capture.getValue();
        assertThat(greeting.getUserId()).isEqualTo(7L);
        assertThat(greeting.getPeriod()).isEqualTo("20261110060");
        assertThat(greeting.getCommandType()).isEqualTo("DAILY_CLOSING_GREETING");
        assertThat(greeting.getExternalId()).isEqualTo("daily-closing-greeting:20261110060");
        assertThat(greeting.getReply()).isEqualTo("祝各位老板生活愉快 好梦哦💤");
        assertThat(greeting.getCreateTime()).isEqualTo(drawTime.plusSeconds(1));
    }

    private MessageDO message(Long id, String command) {
        MessageDO message = new MessageDO();
        message.setId(id);
        message.setCommandType(command);
        message.setPeriod("20260928238");
        message.setCreateTime(LocalDateTime.of(2026, 9, 28, 19, 50).plusSeconds(id));
        return message;
    }

    private DrawDO draw(String period, boolean settled) {
        DrawDO draw = new DrawDO();
        draw.setPeriod(period);
        if (settled) draw.setSettledAt(LocalDateTime.of(2026, 9, 28, 19, 50));
        return draw;
    }
}
