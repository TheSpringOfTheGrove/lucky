package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.common.pojo.PageResult;
import com.hnz.luck5.framework.tenant.core.context.TenantContextHolder;
import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LotteryMessageQueryTest {

    private LotteryServiceImpl service;
    private MessageMapper messageMapper;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(1L);
        service = new LotteryServiceImpl();
        messageMapper = mock(MessageMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "robotReplyTemplate", new LotteryRobotReplyTemplate());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void splitsRobotReplyAndMemberContentAndPaginatesDisplayRows() {
        MessageDO bet = message(2L, "玩家A", "20260810001", "大100", "@玩家A\n下注成功");
        MessageDO chat = message(1L, "玩家B", "20260810001", "今天开奖吗", "");
        stubDisplayPage(List.of(bet, chat));

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
        stubDisplayPage(List.of(bet));
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
        stubDisplayPage(List.of(autoProxyBet, drawResult));

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
                drawResult, viewer, Map.of(), Map.of()))
                .containsEntry("drawImage", "/app-api/lottery/room/messages/2/draw-image");

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
    void canceledRoomBetKeepsOriginalReceiptAndSeparateRobotNotice() {
        MessageDO bet = message(4L, "玩家A", "20260810001", "大100",
                "@玩家A\n[挂牌时间]001\n大100\n【户型审核成功】√√\n\n点击退码");
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
                .contains("[挂牌时间]001", "【户型审核成功】", "点击退码")
                .doesNotContain("tui碼成功");
        stubDisplayPage(List.of(bet));
        Map<String, Object> auditReply = service.getMessages(new LotteryReqVO.MessagePage()).getList().get(0);
        assertThat(auditReply.get("content")).isEqualTo(roomMessage.get("reply"));

        MessageDO notice = message(5L, "玩家A", "20260810001", "",
                "@玩家A\n[挂牌时间]20260810001\n【编号】： 1\n大100\n【套内】：1"
                        + "\n【退面积】：100.00\n【面积】：150.00\ntui碼成功");
        notice.setCommandType(LotteryCancelNoticePublisher.COMMAND_TYPE);
        notice.setMemberId("M-1");
        notice.setOrderId("O-1");
        MemberDO otherViewer = new MemberDO();
        otherViewer.setId("M-2");
        otherViewer.setName("玩家B");
        Map<String, Object> sharedNotice = ReflectionTestUtils.invokeMethod(service, "roomMessageMap",
                notice, otherViewer, Map.of(), Map.of());
        assertThat(sharedNotice).containsEntry("own", false).containsEntry("content", "");
        assertThat(sharedNotice.get("reply")).asString().contains("【退面积】：100.00", "tui碼成功");
    }

    @Test
    void legacySuccessfulReceiptAndRoomUseTheSameCancelLabel() {
        MessageDO bet = message(5L, "玩家A", "20260810001", "大100", "@玩家A\n【户型审核成功】√√");
        bet.setCommandType("BET");
        bet.setStatus("成功");
        bet.setOrderId("O-1");
        bet.setMemberId("M-1");
        stubDisplayPage(List.of(bet));
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
        stubDisplayPage(List.of(draw));
        MemberDO viewer = new MemberDO();
        viewer.setId("M-1");
        Map<String, Object> room = ReflectionTestUtils.invokeMethod(service, "roomMessageMap",
                draw, viewer, Map.of(), Map.of());
        assertThat(service.getMessages(new LotteryReqVO.MessagePage()).getList().get(0).get("content"))
                .isEqualTo(room.get("reply")).isEqualTo("^^--| 215期开奖结果-9|9|7|9|6|和");
    }

    @Test
    void mapperBuildsScopedCountAndPageQueries() {
        Configuration configuration = new Configuration();
        configuration.addMapper(MessageMapper.class);
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", 1L);
        params.put("userId", 2L);
        params.put("period", null);
        params.put("content", "开奖号码");
        params.put("nickname", "玩家A");
        params.put("nicknameCanMatchRobot", false);
        params.put("offset", 20L);
        params.put("pageSize", 10);
        String countSql = configuration.getMappedStatement(MessageMapper.class.getName() + ".countDisplayRows")
                .getBoundSql(params).getSql();
        String pageSql = configuration.getMappedStatement(MessageMapper.class.getName() + ".selectDisplayPage")
                .getBoundSql(params).getSql();
        assertThat(countSql).contains("m.tenant_id = ?", "m.user_id = ?", "row_kind.kind = 0",
                "row_kind.kind = 1", "LOCATE(LOWER(?), LOWER(m.reply))");
        assertThat(pageSql).contains("ORDER BY m.create_time DESC, m.id DESC, row_kind.kind ASC",
                "LIMIT ? OFFSET ?");
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

    private void stubDisplayPage(List<MessageDO> messages) {
        List<Map<String, Object>> displayRows = messages.stream()
                .sorted(Comparator.comparing(MessageDO::getCreateTime).thenComparing(MessageDO::getId).reversed())
                .flatMap(message -> ReflectionTestUtils.<List<Map<String, Object>>>invokeMethod(
                        service, "messageDisplayRows", message).stream())
                .toList();
        when(messageMapper.countDisplayRows(any(), nullable(Long.class), nullable(String.class),
                nullable(String.class), nullable(String.class), anyBoolean()))
                .thenAnswer(call -> filteredRows(displayRows, call.getArgument(3), call.getArgument(4),
                        call.getArgument(2)).size() * 1L);
        when(messageMapper.selectDisplayPage(any(), nullable(Long.class), nullable(String.class),
                nullable(String.class), nullable(String.class), anyBoolean(), anyLong(), anyInt()))
                .thenAnswer(call -> {
                    List<Map<String, Object>> filtered = filteredRows(displayRows, call.getArgument(3),
                            call.getArgument(4), call.getArgument(2));
                    int from = (int) Math.min((long) call.getArgument(6), filtered.size());
                    int to = Math.min(from + (int) call.getArgument(7), filtered.size());
                    return filtered.subList(from, to).stream().map(row -> {
                        MessageMapper.MessagePageEntry entry = new MessageMapper.MessagePageEntry();
                        entry.setMessageId(Long.valueOf(row.get("id").toString().split("-")[1]));
                        entry.setKind("robot".equals(row.get("kind")) ? 0 : 1);
                        return entry;
                    }).toList();
                });
        when(messageMapper.selectBatchIds(any(Collection.class))).thenAnswer(call -> {
            Collection<?> ids = call.getArgument(0);
            return messages.stream().filter(message -> ids.contains(message.getId())).toList();
        });
    }

    private List<Map<String, Object>> filteredRows(List<Map<String, Object>> rows, String content,
                                                    String nickname, String period) {
        return rows.stream().filter(row -> (period == null || row.get("period").toString().contains(period))
                        && (content == null || row.get("content").toString().contains(content))
                        && (nickname == null || row.get("sender").toString().contains(nickname)
                            || row.get("sourceMember").toString().contains(nickname)))
                .toList();
    }
}
