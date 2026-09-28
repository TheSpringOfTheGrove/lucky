package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.common.pojo.PageResult;
import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LotteryMessageQueryTest {

    private LotteryServiceImpl service;
    private MessageMapper messageMapper;

    @BeforeEach
    void setUp() {
        service = new LotteryServiceImpl();
        messageMapper = mock(MessageMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "robotReplyTemplate", new LotteryRobotReplyTemplate());
    }

    @Test
    void splitsRobotReplyAndMemberContentAndPaginatesDisplayRows() {
        MessageDO bet = message(2L, "玩家A", "20260810001", "大100", "@玩家A\n下注成功");
        MessageDO chat = message(1L, "玩家B", "20260810001", "今天开奖吗", "");
        when(messageMapper.selectList(any())).thenReturn(List.of(bet, chat));

        LotteryReqVO.MessagePage firstPage = new LotteryReqVO.MessagePage();
        firstPage.setPageNo(1);
        firstPage.setPageSize(2);
        PageResult<Map<String, Object>> first = service.getMessages(firstPage);

        assertThat(first.getTotal()).isEqualTo(3);
        assertThat(first.getList()).extracting(row -> row.get("sender"))
                .containsExactly("机器人", "玩家A");
        assertThat(first.getList().get(0).get("content")).isEqualTo("@玩家A\n下注成功");

        firstPage.setPageNo(2);
        PageResult<Map<String, Object>> second = service.getMessages(firstPage);
        assertThat(second.getList()).extracting(row -> row.get("sender")).containsExactly("玩家B");
    }

    @Test
    void nicknameSearchKeepsThePlayersRobotReplyTogether() {
        MessageDO bet = message(2L, "玩家A", "20260810001", "大100", "@玩家A\n下注成功");
        when(messageMapper.selectList(any())).thenReturn(List.of(bet));
        LotteryReqVO.MessagePage request = new LotteryReqVO.MessagePage();
        request.setNickname("玩家A");

        PageResult<Map<String, Object>> result = service.getMessages(request);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getList()).extracting(row -> row.get("sender"))
                .containsExactly("机器人", "玩家A");
    }

    @Test
    void includesAutoProxyAndDrawSnapshotsWithoutInventingMemberRows() {
        MessageDO autoProxyBet = message(3L, "A01", "20260810181", "大100", "@A01\n【户型审核成功】√√");
        autoProxyBet.setMessageType("AUTO_PROXY");
        autoProxyBet.setCommandType("BET");
        MessageDO drawResult = message(2L, "", "20260810180", "", "180期开奖结果-0|6|2|2|2");
        drawResult.setCommandType("DRAW_RESULT");
        drawResult.setContent("06222");
        drawResult.setDrawImage("<svg>180 14:00 06222</svg>");
        when(messageMapper.selectList(any())).thenReturn(List.of(autoProxyBet, drawResult));

        LotteryReqVO.MessagePage request = new LotteryReqVO.MessagePage();
        PageResult<Map<String, Object>> result = service.getMessages(request);

        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getList()).extracting(row -> row.get("sender"))
                .containsExactly("机器人", "A01", "机器人");
        assertThat(result.getList()).extracting(row -> row.get("content"))
                .containsExactly("@A01\n【户型审核成功】√√", "大100", "^^--| 180期开奖结果-0|6|2|2|2|虎");
        Map<String, Object> drawRow = result.getList().get(2);
        assertThat(drawRow).containsEntry("kind", "robot").containsEntry("commandType", "DRAW_RESULT");
        String image = drawRow.get("drawImage").toString();
        assertThat(new String(Base64.getDecoder().decode(image.substring(image.indexOf(',') + 1)),
                StandardCharsets.UTF_8)).isEqualTo(drawResult.getDrawImage());
        MemberDO viewer = new MemberDO();
        viewer.setId("M-1");
        assertThat(ReflectionTestUtils.<Map<String, Object>>invokeMethod(service, "roomMessageMap",
                drawResult, viewer, Map.of(), Map.of())).containsEntry("drawImage", image);

        request.setPageSize(2);
        request.setPageNo(2);
        assertThat(service.getMessages(request).getList()).extracting(row -> row.get("id"))
                .containsExactly("robot-2");

        request.setPageNo(1);
        request.setContent("06222");
        assertThat(service.getMessages(request).getList()).isEmpty(); // no fake raw-number player message
        request.setContent("期开奖结果");
        assertThat(service.getMessages(request).getTotal()).isEqualTo(1);
    }

    @Test
    void canceledRoomBetKeepsReceiptButNeverRestoresCancelLink() {
        MessageDO bet = message(4L, "玩家A", "20260810001", "大100",
                "@玩家A\n[挂牌时间]001\n大100\n【户型审核成功】√√\n\n点击退码\n已退码");
        bet.setCommandType("BET");
        bet.setStatus("已退码");
        bet.setOrderId("O-1");
        bet.setMemberId("M-1");
        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setName("玩家A");

        Map<String, Object> roomMessage = ReflectionTestUtils.invokeMethod(service, "roomMessageMap",
                bet, member, Map.of(), Map.of());

        assertThat(roomMessage).containsEntry("status", "已退码");
        assertThat(roomMessage.get("reply").toString())
                .contains("[挂牌时间]001", "【户型审核成功】", "已退码")
                .doesNotContain("点击退码");
        when(messageMapper.selectList(any())).thenReturn(List.of(bet));
        Map<String, Object> auditReply = service.getMessages(new LotteryReqVO.MessagePage()).getList().get(0);
        assertThat(auditReply.get("content")).isEqualTo(roomMessage.get("reply"));
    }

    @Test
    void legacySuccessfulReceiptAndRoomUseTheSameCancelLabel() {
        MessageDO bet = message(5L, "玩家A", "20260810001", "大100", "@玩家A\n【户型审核成功】√√");
        bet.setCommandType("BET");
        bet.setStatus("成功");
        bet.setOrderId("O-1");
        bet.setMemberId("M-1");
        when(messageMapper.selectList(any())).thenReturn(List.of(bet));
        MemberDO viewer = new MemberDO();
        viewer.setId("M-1");
        Map<String, Object> room = ReflectionTestUtils.invokeMethod(service, "roomMessageMap",
                bet, viewer, Map.of(), Map.of());
        assertThat(service.getMessages(new LotteryReqVO.MessagePage()).getList().get(0).get("content"))
                .isEqualTo(room.get("reply")).asString().endsWith("点击退码");
    }

    @Test
    void replaysLegacyCommaSeparatedDrawWithFiveBallsAndSameRoomReply() {
        MessageDO draw = message(6L, "", "20260928215", "9,9,7,9,6",
                "^^--| 215期开奖结果-9|,|9|,|7|,|9|,|6|和");
        draw.setCommandType("DRAW_RESULT");
        draw.setDrawImage("<svg>saved image</svg>");
        when(messageMapper.selectList(any())).thenReturn(List.of(draw));
        MemberDO viewer = new MemberDO();
        viewer.setId("M-1");
        Map<String, Object> room = ReflectionTestUtils.invokeMethod(service, "roomMessageMap",
                draw, viewer, Map.of(), Map.of());
        assertThat(service.getMessages(new LotteryReqVO.MessagePage()).getList().get(0).get("content"))
                .isEqualTo(room.get("reply")).isEqualTo("^^--| 215期开奖结果-9|9|7|9|6|和");
    }

    private MessageDO message(Long id, String member, String period, String content, String reply) {
        MessageDO message = new MessageDO();
        message.setId(id);
        message.setMember(member);
        message.setPeriod(period);
        message.setContent(content);
        message.setReply(reply);
        message.setMessageType("PLAYER");
        message.setCreateTime(LocalDateTime.of(2026, 8, 10, 14, 0).plusSeconds(id));
        message.setProcessedAt(message.getCreateTime().plusSeconds(1));
        return message;
    }
}
