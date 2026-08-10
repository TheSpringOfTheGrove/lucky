package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.common.pojo.PageResult;
import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
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
    void includesAutoProxyRoomMessagesAndExcludesDrawResults() {
        MessageDO autoProxyBet = message(3L, "A01", "20260810181", "大100", "@A01\n【户型审核成功】√√");
        autoProxyBet.setMessageType("AUTO_PROXY");
        autoProxyBet.setCommandType("BET");
        MessageDO drawResult = message(2L, "", "20260810180", "", "180期开奖结果-0|6|2|2|2");
        drawResult.setCommandType("DRAW_RESULT");
        when(messageMapper.selectList(any())).thenReturn(List.of(autoProxyBet, drawResult));

        LotteryReqVO.MessagePage request = new LotteryReqVO.MessagePage();
        PageResult<Map<String, Object>> result = service.getMessages(request);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getList()).extracting(row -> row.get("sender"))
                .containsExactly("机器人", "A01");
        assertThat(result.getList()).extracting(row -> row.get("content"))
                .containsExactly("@A01\n【户型审核成功】✓✓", "大100");
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
