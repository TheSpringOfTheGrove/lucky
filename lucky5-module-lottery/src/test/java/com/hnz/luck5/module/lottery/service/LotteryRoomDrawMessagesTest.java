package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.module.lottery.dal.dataobject.DrawDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
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
