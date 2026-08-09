package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.controller.app.vo.LotteryRoomReqVO;

import java.util.Map;

public interface LotteryService {

    Map<String, Object> getBootstrap();

    void setSwitch(String key, Boolean value);

    void setRoom(Boolean open);

    void saveConfig(LotteryReqVO.Config reqVO);

    Map<String, Object> testConfig(LotteryReqVO.Config reqVO);

    Map<String, Object> syncMarket();

    void saveLinks(LotteryReqVO.LinkConfig reqVO);

    void saveChimaConfig(LotteryReqVO.ChimaConfig reqVO);

    void bindIntegration(String key, LotteryReqVO.Integration reqVO);

    void unbindIntegration(String key);

    String saveMember(LotteryReqVO.Member reqVO);

    void transferMember(String id, LotteryReqVO.Transfer reqVO);

    String createAmountRequest(String id, LotteryReqVO.Transfer reqVO);

    Map<String, Object> getMemberDetails(String id);

    Map<String, String> getMemberLinks(String id, String origin);

    Map<String, String> rotateMemberLink(String id, String origin);

    void clearMemberFingerprint(String id);

    int clearAllFingerprints();

    int changeMemberAvatar(String id);

    int clearAllMemberFlows(String password);

    void clearMember(String id);

    void deleteMember(String id);

    void saveDiscounts(LotteryReqVO.Discounts reqVO);

    Map<String, Object> applyRebates();

    void clearChimaRecords(String password);

    void auditAmount(String id, LotteryReqVO.Audit reqVO);

    String placeBet(LotteryReqVO.PlaceBet reqVO);

    void cancelOrder(String id);

    void settlePeriod(String period, LotteryReqVO.Settle reqVO);

    Map<String, Object> getIssueStatus();

    void setIssueStatus(String period, String status);

    int settlePendingIssues();

    Map<String, Object> processIncomingMessage(LotteryReqVO.IncomingMessage reqVO);

    void saveOdds(LotteryReqVO.Odds reqVO);

    String savePresetOrder(LotteryReqVO.PresetOrder reqVO);

    void deletePresetOrder(String id);

    String saveQuickCommand(LotteryReqVO.QuickCommand reqVO);

    void deleteQuickCommand(String id);

    String saveFollowOrder(LotteryReqVO.FollowOrder reqVO);

    void deleteFollowOrder(String id);

    void markMessage(Long id, String status);

    Map<String, Object> getRoomSession(LotteryRoomReqVO.Credential reqVO);

    String roomPlaceBet(LotteryRoomReqVO.Bet reqVO);

    Map<String, Object> previewRoomBet(LotteryRoomReqVO.Bet reqVO);

    Map<String, Object> processRoomMessage(LotteryRoomReqVO.Message reqVO);

    String createRoomAmountRequest(LotteryRoomReqVO.Amount reqVO);

    void cancelRoomOrder(String orderId, LotteryRoomReqVO.Credential reqVO);
}
