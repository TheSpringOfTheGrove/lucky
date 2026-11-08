package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.mysql.AmountRecordMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OperationLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LotteryRoomAmountCommandTest {

    @Test
    void playerAmountCommandOnlyCreatesAnInstructionErrorMessage() {
        LotteryServiceImpl service = new LotteryServiceImpl();
        MemberMapper members = mock(MemberMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        AmountRecordMapper amounts = mock(AmountRecordMapper.class);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "messageMapper", messages);
        ReflectionTestUtils.setField(service, "amountRecordMapper", amounts);
        ReflectionTestUtils.setField(service, "roomMessagePolicy", new LotteryRoomMessagePolicy());
        ReflectionTestUtils.setField(service, "robotReplyTemplate", new LotteryRobotReplyTemplate());

        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setName("广西一哥");
        member.setUserId(7L);
        member.setMemberType("PLAYER");
        when(members.selectById("M-1")).thenReturn(member);

        LotteryReqVO.IncomingMessage request = new LotteryReqVO.IncomingMessage();
        request.setMemberId("M-1");
        request.setContent("下分 10000");
        request.setChannel("网页群");
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(service,
                "processIncomingMessageInternal", request, "广西一哥");

        assertThat(result).containsEntry("commandType", "AMOUNT_REJECTED")
                .containsEntry("reply", "@广西一哥\n指令错误");
        ArgumentCaptor<MessageDO> saved = ArgumentCaptor.forClass(MessageDO.class);
        verify(messages).insert(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo("下分 10000");
        assertThat(saved.getValue().getReply()).isEqualTo("@广西一哥\n指令错误");
        verifyNoInteractions(amounts);
    }

    @Test
    void ownerTransferSavesPublicRobotReceiptsForPlayersAndAutomaticProxies() {
        LotteryServiceImpl service = new LotteryServiceImpl();
        MemberMapper members = mock(MemberMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        AmountRecordMapper amounts = mock(AmountRecordMapper.class);
        LotteryBalanceLedgerService ledger = mock(LotteryBalanceLedgerService.class);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "messageMapper", messages);
        ReflectionTestUtils.setField(service, "amountRecordMapper", amounts);
        ReflectionTestUtils.setField(service, "balanceLedgerService", ledger);
        ReflectionTestUtils.setField(service, "operationLogMapper", mock(OperationLogMapper.class));
        ReflectionTestUtils.setField(service, "robotReplyTemplate", new LotteryRobotReplyTemplate());

        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setName("广西一哥");
        member.setUserId(7L);
        member.setMemberType("PLAYER");
        member.setBalance(new BigDecimal("5000"));
        when(members.selectById("M-1")).thenReturn(member);
        doAnswer(invocation -> {
            MemberDO target = invocation.getArgument(0);
            BigDecimal before = target.getBalance();
            BigDecimal after = before.add(invocation.getArgument(1));
            target.setBalance(after);
            return new LotteryBalanceLedgerService.BalanceChange(before, after);
        }).when(ledger).change(any(), any(), any(), any(), any(), any());

        LotteryReqVO.Transfer transfer = new LotteryReqVO.Transfer();
        transfer.setType("上分");
        transfer.setAmount(new BigDecimal("10000"));
        service.transferMember("M-1", transfer);
        transfer.setType("下分");
        service.transferMember("M-1", transfer);
        member.setMemberType("BOT");
        member.setBalance(new BigDecimal("5000"));
        transfer.setType("上分");
        service.transferMember("M-1", transfer);

        ArgumentCaptor<MessageDO> saved = ArgumentCaptor.forClass(MessageDO.class);
        verify(messages, times(3)).insert(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(message -> {
            assertThat(message.getMemberId()).isEqualTo("M-1");
            assertThat(message.getExternalId()).startsWith("admin-transfer:");
        });
        assertThat(saved.getAllValues().get(0).getReply()).isEqualTo("@广西一哥\n加分：10000\n目前：15000");
        assertThat(saved.getAllValues().get(0).getCommandType()).isEqualTo("DEPOSIT_REQUEST");
        assertThat(saved.getAllValues().get(1).getReply()).isEqualTo("@广西一哥\n减分：10000\n目前：5000");
        assertThat(saved.getAllValues().get(1).getCommandType()).isEqualTo("WITHDRAW_REQUEST");
        assertThat(saved.getAllValues().get(2).getChannel()).isEqualTo("网页群");
        assertThat(saved.getAllValues().get(2).getMessageType()).isEqualTo("AUTO_PROXY");
        assertThat(saved.getAllValues().get(2).getReply()).isEqualTo("@广西一哥\n加分：10000\n目前：15000");
    }
}
