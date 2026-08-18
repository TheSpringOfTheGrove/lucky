package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.OddDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LotteryBettingServiceTest {

    private final LotteryBettingService service = new LotteryBettingService();
    private List<OddDO> odds;

    @BeforeEach
    void setUp() {
        odds = List.of(
                odd("regex1d", "一定位", "9"), odd("regex2d", "二定位", "96"),
                odd("regex3d", "三定位", "960"), odd("regex4d", "四定位", "9600"),
                odd("regex2x", "二字现", "9"), odd("regex3x", "三字现", "45"),
                odd("regex4x", "四字现", "360"), odd("regex4d4", "四条", "7000"),
                odd("regexlh", "龙虎", "0"), odd("regexh", "和", "0"));
    }

    @Test
    void parsesBasicConfiguredAndPositionBets() {
        assertThat(service.parse("大100 单50", odds)).hasSize(2);
        assertThat(service.parse("大单各100", odds)).hasSize(2);
        assertThat(service.parse("123/10", odds).get(0))
                .extracting(LotteryBettingService.ParsedBet::play, LotteryBettingService.ParsedBet::selection)
                .containsExactly("三字现", "123");
        assertThat(service.parse("123定/10", odds).get(0))
                .extracting(LotteryBettingService.ParsedBet::play, LotteryBettingService.ParsedBet::selection)
                .containsExactly("三定位", "123");
        assertThat(service.parse("千12百34二定各10", odds)).hasSize(4);
        assertThat(service.parse("12配34配二定各10", odds)).hasSize(48);
    }

    @Test
    void parsesFullFourPositionCartesianCommand() {
        List<LotteryBettingService.ParsedBet> bets = service.parse(
                "千0123456789百0123456789十0123456789尾0123456789各0.1", odds);

        assertThat(bets).hasSize(10_000)
                .allSatisfy(bet -> {
                    assertThat(bet.play()).isEqualTo("四定位");
                    assertThat(bet.amount()).isEqualByComparingTo("0.1");
                });
        assertThat(bets.stream().map(LotteryBettingService.ParsedBet::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1000");
    }

    @Test
    void parsesNumericFixedShorthandFromOnesPosition() {
        assertThat(service.parse("1各1", odds)).containsExactlyElementsOf(service.parse("个1各1", odds));
        assertThat(service.parse("12各1", odds)).containsExactlyElementsOf(service.parse("十1个2各1", odds));
        assertThat(service.parse("123各1", odds)).containsExactlyElementsOf(service.parse("百1十2个3各1", odds));
        assertThat(service.parse("5874各2", odds))
                .containsExactlyElementsOf(service.parse("千5百8十7个4各2", odds));
        assertThat(service.parse("8888各1", odds))
                .containsExactlyElementsOf(service.parse("千8百8十8个8各1", odds));

        assertThat(service.parse("1各1", odds).get(0))
                .extracting(LotteryBettingService.ParsedBet::play, LotteryBettingService.ParsedBet::selection,
                        LotteryBettingService.ParsedBet::amount, LotteryBettingService.ParsedBet::odds)
                .containsExactly("一定位", "XXX1", new BigDecimal("1"), new BigDecimal("9"));
        assertThat(service.parse("12各1", odds).get(0))
                .extracting(LotteryBettingService.ParsedBet::play, LotteryBettingService.ParsedBet::selection)
                .containsExactly("二定位", "XX12");
        assertThat(service.parse("123各1", odds).get(0))
                .extracting(LotteryBettingService.ParsedBet::play, LotteryBettingService.ParsedBet::selection)
                .containsExactly("三定位", "X123");
        assertThat(service.parse("5874各2", odds).get(0))
                .extracting(LotteryBettingService.ParsedBet::play, LotteryBettingService.ParsedBet::selection,
                        LotteryBettingService.ParsedBet::amount, LotteryBettingService.ParsedBet::odds)
                .containsExactly("四定位", "5874", new BigDecimal("2"), new BigDecimal("9600"));
        assertThat(service.parse("8888各1", odds).get(0))
                .extracting(LotteryBettingService.ParsedBet::play, LotteryBettingService.ParsedBet::selection,
                        LotteryBettingService.ParsedBet::odds)
                .containsExactly("四定位", "8888", new BigDecimal("9600"));

        assertThat(service.isWinning(service.parse("1各1", odds).get(0), service.deriveDraw("00010"))).isTrue();
        assertThat(service.isWinning(service.parse("1各1", odds).get(0), service.deriveDraw("10000"))).isFalse();
        assertThat(service.isWinning(service.parse("12各1", odds).get(0), service.deriveDraw("00120"))).isTrue();
        assertThat(service.isWinning(service.parse("123各1", odds).get(0), service.deriveDraw("01230"))).isTrue();
        assertThat(service.isWinning(service.parse("8888各1", odds).get(0), service.deriveDraw("88880"))).isTrue();
        assertThat(service.isWinning(service.parse("8888各1", odds).get(0), service.deriveDraw("88808"))).isFalse();
    }

    @Test
    void matchesOriginalReferenceFilters() {
        assertThat(service.parse("二现含12各3", odds)).hasSize(19);
        assertThat(service.parse("三现取三兄弟各1", odds)).hasSize(10);
        assertThat(service.parse("四现取双双重各2", odds)).hasSize(55);
        assertThat(service.parse("四定取千百合5十个合6各1", odds)).hasSize(100);
        assertThat(service.parse("千1二定上奖12各1", odds)).extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("11XX", "12XX", "1X1X", "1X2X", "1XX1", "1XX2");
        assertThat(service.parse("三现全倒112各1", odds)).hasSize(1);
        assertThat(service.parse("五位二定千12五34各1", odds)).hasSize(4);
        assertThat(service.parse("千12五34五位二定各1", odds)).hasSize(4);
        assertThat(service.parse("千12五位二定五34各1", odds)).hasSize(20);
        assertThatThrownBy(() -> service.parse("除12配34配二定各1", odds)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void parsesMigratedQuickCommandCorpus() {
        List<String> commands = List.of(
                "11335566778899倒四定各0.5",
                "2456789百0245689个各20",
                "123456780头123467890百234567890十0123456789个。两数合012345除三重除三兄弟各0.5",
                "6789千13579百0123457十各5",
                "头13579百24680十1245789各0.5",
                "百13579十1245798尾02468各0.5",
                "023456789千023456789百012345679十012345679个。含016789千十合01234579千个合23456789百十合01245679除三重除两双重各0.5",
                "1243790头1234567百1234789尾各2",
                "0123456789千百十个。含347两数合024取两兄弟各0.5",
                "百02468十1245789尾13579各0.5");
        commands.forEach(command -> assertThat(service.parse(command, odds)).as(command).isNotEmpty());
    }

    @Test
    void appliesConfiguredDragonTigerAndTieOddsAndLimits() {
        OddDO dragonTiger = odd("regexlh", "龙虎", "2");
        dragonTiger.setMinLimit(new BigDecimal("100"));
        OddDO tie = odd("regexh", "和", "9");
        tie.setMinLimit(new BigDecimal("100"));
        List<OddDO> configuredOdds = List.of(dragonTiger, tie);

        assertThat(service.parse("龙虎和各100", configuredOdds))
                .extracting(LotteryBettingService.ParsedBet::odds)
                .containsExactly(new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("9"));
        assertThatThrownBy(() -> service.parse("龙50", configuredOdds)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.parse("和50", configuredOdds)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void reportsDisabledPlaySeparatelyFromAmountLimit() {
        assertThatThrownBy(() -> service.parse("龙100", odds))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("当前配置未开放该玩法");
    }

    @Test
    void derivesAndSettlesDraws() {
        LotteryBettingService.DrawResult draw = service.deriveDraw("1379");
        assertThat(service.isWinning(new LotteryBettingService.ParsedBet(
                "二定位", "13XX", BigDecimal.ONE, BigDecimal.ONE), draw)).isTrue();
        assertThat(service.isWinning(new LotteryBettingService.ParsedBet(
                "二定位", "14XX", BigDecimal.ONE, BigDecimal.ONE), draw)).isFalse();
        assertThat(service.isWinning(new LotteryBettingService.ParsedBet(
                "三字现", "139", BigDecimal.ONE, BigDecimal.ONE), draw)).isTrue();
    }

    @Test
    void excludesFifthBallFromEverySettlementRule() {
        LotteryBettingService.DrawResult xianDraw = service.deriveDraw("74958");
        assertThat(service.isWinning(new LotteryBettingService.ParsedBet(
                "三字现", "985", BigDecimal.ONE, BigDecimal.ONE), xianDraw)).isFalse();
        assertThat(service.isWinning(new LotteryBettingService.ParsedBet(
                "三字现", "795", BigDecimal.ONE, BigDecimal.ONE), xianDraw)).isTrue();

        LotteryBettingService.DrawResult repeatedDraw = service.deriveDraw("11121");
        assertThat(service.isWinning(new LotteryBettingService.ParsedBet(
                "四条", "1111", BigDecimal.ONE, BigDecimal.ONE), repeatedDraw)).isFalse();

        assertThat(service.deriveDraw("44449"))
                .extracting(LotteryBettingService.DrawResult::bigSmall,
                        LotteryBettingService.DrawResult::oddEven,
                        LotteryBettingService.DrawResult::dragonTiger)
                .containsExactly("小", "双", "和");
        assertThat(service.deriveDraw("10090").dragonTiger()).isEqualTo("虎");
    }

    @Test
    void matchesFourPositionBetsAgainstFirstFourDigitsOfLucky5Draw() {
        LotteryBettingService.ParsedBet regular = service.parse("千1各1", odds).get(0);
        assertThat(regular.selection()).isEqualTo("1XXX");
        assertThat(service.isWinning(regular, service.deriveDraw("10000"))).isTrue();
        assertThat(service.isWinning(regular, service.deriveDraw("01000"))).isFalse();

        List<LotteryBettingService.ParsedBet> hundredAndTen = service.parse(
                "百3456789十3456789除双重各20", odds);
        assertThat(hundredAndTen).hasSize(42);
        assertThat(hundredAndTen.stream()
                .filter(item -> service.isWinning(item, service.deriveDraw("66576")))
                .map(LotteryBettingService.ParsedBet::selection))
                .containsExactly("X65X");

        LotteryBettingService.ParsedBet fifth = service.parse("五位二定千1五2各1", odds).get(0);
        assertThat(fifth.selection()).isEqualTo("1XXX2");
        assertThat(service.isWinning(fifth, service.deriveDraw("10002"))).isFalse();
        assertThat(service.isWinning(fifth, service.deriveDraw("10003"))).isFalse();
    }

    private OddDO odd(String code, String play, String rate) {
        OddDO odd = new OddDO();
        odd.setCode(code);
        odd.setPlay(play);
        odd.setItem("");
        odd.setRate(new BigDecimal(rate));
        odd.setMinLimit(new BigDecimal("0.1"));
        odd.setMaxLimit(new BigDecimal("10000"));
        odd.setStatus("启用");
        return odd;
    }
}
