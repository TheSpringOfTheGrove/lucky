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
    void parsesReverseFixedAliasWithModeBeforeReverse() {
        assertThat(service.parse("253二定倒各5", odds))
                .containsExactlyElementsOf(service.parse("253倒二定各5", odds));
        assertThat(service.parse("123三定倒各1", odds))
                .containsExactlyElementsOf(service.parse("123倒三定各1", odds));

        List<LotteryBettingService.ParsedBet> alias = service.parse("223344455667788四定倒各1", odds);
        List<LotteryBettingService.ParsedBet> canonical = service.parse("223344455667788倒四定各1", odds);
        assertThat(alias).hasSize(2_250).containsExactlyElementsOf(canonical)
                .allSatisfy(bet -> {
                    assertThat(bet.play()).isEqualTo("四定位");
                    assertThat(bet.amount()).isEqualByComparingTo("1");
                    assertThat(bet.odds()).isEqualByComparingTo("9600");
                });
        assertThat(alias.stream().map(LotteryBettingService.ParsedBet::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("2250");
    }

    @Test
    void parsesHistoricalFixedPoolAndReverseAliases() {
        assertThat(service.parse("0759三定各0.1", odds)).hasSize(96);
        assertThat(service.parse("56790三定各0.2", odds)).hasSize(240);
        assertThat(service.parse("5679倒两定各1", odds)).hasSize(72);
        assertThat(service.parse("6651倒各1", odds)).hasSize(12);
    }

    @Test
    void splitsCombinedCommandsAndPreservesDuplicateCommands() {
        assertThat(service.parse("5679各1.1 9765各1", odds)).hasSize(2);
        assertThat(service.parse("0759三定各1,0759三定各1", odds)).hasSize(192);
        assertThat(service.parse("千01234百01234十01234尾01234各1 千01234百01234十01234尾01234各1", odds))
                .hasSize(1_250);
        assertThat(service.splitCommandsForDisplay("0759三定各1,0759三定各1"))
                .containsExactly("0759三定各1", "0759三定各1");
    }

    @Test
    void matchesHistoricalFourPositionFilterCounts() {
        assertThat(service.parse(
                "除个4四定取千百十个合023697458百十个合623957104两数合0268除对数49除单单大双除四兄弟除四重除两双重各1", odds).size()).isEqualTo(5_823);
        assertThat(service.parse(
                "除个7四定取千百合473018652百个合237460518三数合01258除对数38各0.5", odds).size()).isEqualTo(6_105);
        assertThat(service.parse(
                "配01234配56789四定除千百十个合4三数合54321除单双双双取两兄弟各1.6", odds).size()).isEqualTo(4_876);
        assertThat(service.parse(
                "四定千123457906百123567890十123457890取千百十个合123456789取千百个合012345689除三重除四重除四兄弟除对数371各0.8", odds).size()).isEqualTo(5_409);
        assertThat(service.parse(
                "千百十尾23457890除大大大大除小小小小除单单单单除双双双双除千百十个合16各0.1", odds).size()).isEqualTo(2_590);
    }

    @Test
    void matchesHistoricalInheritedFilterActionCounts() {
        assertThat(service.parse(
                "千9375百234571908十123457896取两数合1357三数合13579248除三兄弟三重各1", odds)).hasSize(139);
        assertThat(service.parse(
                "千9375百234571908十123457896取两数合2468三数合13579248除三兄弟三重各1", odds)).hasSize(206);
        assertThat(service.parse(
                "千234567980百234507896尾3957除三兄弟三重取两数合2468三数合13579248各1", odds)).hasSize(200);
    }

    @Test
    void parsesHistoricalConflictingFilterChainWithoutHardCodingItsReply() {
        // 该条存档回执与同一文件中其它已验证样本的筛选定义冲突，只锁定兼容解析能力。
        assertThat(service.parse(
                "千百十个1357902468除千百十个合7除千百合0除十个合7两数合0268除对数27除三重除两双重除三兄弟各0.6", odds)).isNotEmpty();
    }

    @Test
    void matchesHistoricalCombinedPairedCommandCount() {
        String suffix = "配四定取千123456789两数合0248含23568除四兄弟除对数27除值36值36各0.1";
        String content = List.of("035", "203", "028", "258", "235", "269", "429",
                        "640", "670", "709", "358", "047", "368", "568")
                .stream().map(right -> "13680配" + right + suffix)
                .collect(java.util.stream.Collectors.joining(" "));

        assertThat(service.parse(content, odds)).hasSize(69_141);
    }

    @Test
    void matchesRemainingHistoricalCorpusCounts() {
        assertThat(service.parse(
                "089613三定各0.3 7890三定各0.3 8902三定各0.3 0136三定各0.3 1234三定各0.3 0123三定各0.3", odds)).hasSize(960);
        assertThat(service.parse("1133445599778800倒四定各1.3", odds)).hasSize(3_864);
        assertThat(service.parse(
                "123456780头123467890百234567890十。两数合234567除三重除三兄弟各7", odds)).hasSize(650);
        assertThat(service.parse(
                "123456780头123467890百234567890十012346789个。两数合012345除三重各0.5", odds)).hasSize(6_264);
        assertThat(service.parse("12364790头12307698尾除双重各65", odds)).hasSize(57);
        assertThat(service.parse(
                "6516各8.8 6156各8.8 6651倒各0.6 5561倒各0.6 1156倒各0.6", odds)).hasSize(38);
        assertThat(service.parse(
                "千234567980百234507896尾3957除三兄弟三重取两数合1357三数合13579248各0.9", odds)).hasSize(166);
        assertThat(service.parse("千364578百154378个024568各23", odds)).hasSize(216);
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
