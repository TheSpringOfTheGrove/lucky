package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.OddDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LotteryBettingServiceTest {

    private final LotteryBettingService service = new LotteryBettingService();
    private List<OddDO> odds;

    @BeforeEach
    void setUp() {
        odds = List.of(
                odd("regex1d", "一定位", "9"), odd("regex2d", "二定位", "96"),
                odd("regex5d2", "五位二定", "88"),
                odd("regex3d", "三定位", "960"), odd("regex4d", "四定位", "9600"),
                odd("regex2x", "二字现", "9"), odd("regex3x", "三字现", "45"),
                odd("regex4x", "四字现", "360"), odd("regex4d4", "四条", "7000"),
                odd("regexlh", "龙虎", "0"), odd("regexh", "和", "0"));
    }

    @Test
    void followsQuickTranslationDirectNumberAndPoolRules() {
        assertThat(service.parse("12XX=1", odds))
                .extracting(LotteryBettingService.ParsedBet::play,
                        LotteryBettingService.ParsedBet::selection)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("二定位", "12XX"));
        assertThat(service.parse("123X各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("123X");
        assertThat(service.parse("1XXX2各1", odds))
                .extracting(LotteryBettingService.ParsedBet::play,
                        LotteryBettingService.ParsedBet::selection)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("五位二定", "1XXX2"));
        assertThat(service.parse("123|123XX各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("11XX", "12XX", "13XX", "21XX", "22XX", "23XX",
                        "31XX", "32XX", "33XX");
        assertThat(service.parse("123XX123各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("1XX1", "1XX2", "1XX3", "2XX1", "2XX2", "2XX3",
                        "3XX1", "3XX2", "3XX3");
        assertThat(service.parse("12各1", odds))
                .extracting(LotteryBettingService.ParsedBet::play,
                        LotteryBettingService.ParsedBet::selection)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("二字现", "12"));
        assertThat(service.parse("123各1", odds))
                .extracting(LotteryBettingService.ParsedBet::play,
                        LotteryBettingService.ParsedBet::selection)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("三字现", "123"));
        assertThat(service.parse("1234=1", odds))
                .extracting(LotteryBettingService.ParsedBet::play,
                        LotteryBettingService.ParsedBet::selection)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("四定位", "1234"));
        assertThat(service.parse("1234现各1", odds).get(0).play()).isEqualTo("四字现");
        assertThat(service.parse("12xx=1", odds)).containsExactlyElementsOf(service.parse("12++=1", odds));
        assertThat(service.parse("头12尾34各1", odds)).containsExactlyElementsOf(
                service.parse("千12个34各1", odds));
        assertThat(service.parse("1234\n2345\n3456\n=1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("1234", "2345", "3456");
        assertThat(service.parse("千12二字定\n百34二字定\n=1", odds)).hasSize(40);
    }

    @Test
    void followsQuickTranslationPositionAndPlayRules() {
        assertThat(service.parse("千123百123十123个123各1", odds)).hasSize(81);
        assertThat(service.parse("123千123百123十各1", odds)).hasSize(27);
        assertThat(service.parse("千百123各1", odds)).hasSize(9);
        assertThat(service.parse("中肚123各1", odds)).hasSize(9);
        assertThat(service.parse("千大百小各1", odds)).hasSize(25);
        assertThat(service.parse("123二字定各1", odds)).hasSize(36);
        assertThat(service.parse("123二字现各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("12", "13", "23");
        assertThat(service.parse("千123四字定各1", odds)).hasSize(3_000);
        assertThat(service.parse("千12二字定各1", odds)).hasSize(20);
        assertThat(service.parse("千12三字定=1", odds)).hasSize(200);
        assertThat(service.parse("个12二字定各1", odds)).hasSize(20);
        assertThat(service.parse("千12各1", odds)).hasSize(20)
                .allSatisfy(item -> assertThat(item.play()).isEqualTo("二定位"));
    }

    @Test
    void followsQuickTranslationDocumentedFilterCounts() {
        assertThat(service.parse("千12四字定取百双除个单各1", odds)).hasSize(500);
        assertThat(service.parse("千12直码取百双不要个单各1", odds)).hasSize(500);
        assertThat(service.parse("千123百345除双重各1", odds)).hasSize(8);
        assertThat(service.parse(
                "千12百345四字定两数合45值范围15-35除个1234除双重除二兄弟各1", odds))
                .hasSize(31);
        assertThat(service.parse("千123五3456两数合45678除双重各1", odds)).hasSize(10);
    }

    @Test
    void followsQuickTranslationFifthPositionRules() {
        assertThat(service.parse("123XXX123各1", odds)).hasSize(9)
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("1XXX1", "1XXX2", "1XXX3", "2XXX1", "2XXX2", "2XXX3",
                        "3XXX1", "3XXX2", "3XXX3");
        assertThat(service.parse("千123五123各1", odds)).hasSize(9);
        assertThat(service.parse("千123五123除双重各1", odds)).hasSize(6);
        assertThat(service.parse("五123各1", odds)).hasSize(30)
                .allSatisfy(item -> {
                    assertThat(item.play()).isEqualTo("五位二定");
                    assertThat(item.selection()).matches("[0-9]XXX[123]");
                });
        assertThat(service.parse("五123跑45各1", odds)).hasSize(24);
    }

    @Test
    void rejectsQuickTranslationAmbiguityAndInvalidAmounts() {
        assertThatThrownBy(() -> service.parse("1234=1-2", odds)).hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("12345各1", odds)).hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("12XXXX各1", odds)).hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("千123百123十123二字定各1", odds))
                .hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("12345二字定取千12除千34各1", odds))
                .hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("千123二字定,百123二字定各1", odds))
                .hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("千123二字定 百123二字定各1", odds))
                .hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("1234=1\n2345\n=2", odds))
                .hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("12三字定各1", odds))
                .hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("1234=0.001", odds)).hasMessageContaining("无法识别");
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
    void treatsLeadingDecimalAmountsAsZeroPointAmounts() {
        assertThat(service.parse("大.2", odds)).singleElement()
                .satisfies(item -> assertThat(item.amount()).isEqualByComparingTo("0.2"));
        assertThat(service.parse("123/.2", odds)).singleElement()
                .satisfies(item -> assertThat(item.amount()).isEqualByComparingTo("0.2"));
        assertThat(service.parse("0759三定各.2", odds))
                .hasSize(96)
                .allSatisfy(item -> assertThat(item.amount()).isEqualByComparingTo("0.2"));
        assertThat(service.parse("24567861倒三定/.2", odds))
                .allSatisfy(item -> assertThat(item.amount()).isEqualByComparingTo("0.2"));
    }

    @Test
    void parsesSeparatedNumericSlashBetsAsIndividualCommands() {
        for (String content : List.of("123/1\n456/1\n564/1", "123/1;456/1;564/1;")) {
            List<LotteryBettingService.ParsedCommand> commands = service.parseCommands(content, odds);
            assertThat(commands).extracting(LotteryBettingService.ParsedCommand::content)
                    .containsExactly("123/1", "456/1", "564/1");
            assertThat(commands).allSatisfy(command -> {
                assertThat(command.items()).hasSize(1);
                assertThat(command.items().get(0).play()).isEqualTo("三字现");
                assertThat(command.items().get(0).amount()).isEqualByComparingTo("1");
            });
            assertThat(service.parse(content, odds))
                    .extracting(LotteryBettingService.ParsedBet::selection)
                    .containsExactly("123", "456", "564");
        }
        assertThat(service.parse("123/1;123/1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("123", "123");
    }

    @Test
    void fourPositionSizePatternIsOneFixedCommandWithCartesianDigits() {
        List<LotteryBettingService.ParsedBet> bets = service.parse("大大小小各1", odds);

        assertThat(bets).hasSize(625)
                .allSatisfy(bet -> {
                    assertThat(bet.play()).isEqualTo("四定位");
                    assertThat(bet.amount()).isEqualByComparingTo("1");
                });
        assertThat(bets).extracting(LotteryBettingService.ParsedBet::selection)
                .contains("5500", "9944")
                .doesNotContain("4499", "5555");
        assertThat(bets).containsExactlyElementsOf(service.parse(
                "千56789百56789十01234个01234各1", odds));
        assertThat(service.parse("大小各1", odds)).hasSize(2);
    }

    @Test
    void fourPositionMixedSizeAndParityPatternsUseFiveDigitsPerPosition() {
        List<LotteryBettingService.ParsedBet> mixed = service.parse("大双小单各1", odds);
        List<LotteryBettingService.ParsedBet> parity = service.parse("单单双双各1", odds);

        assertThat(mixed).hasSize(625).containsExactlyElementsOf(service.parse(
                "千56789百02468十01234个13579各1", odds));
        assertThat(parity).hasSize(625).containsExactlyElementsOf(service.parse(
                "千13579百13579十02468个02468各1", odds));
        assertThat(mixed).extracting(LotteryBettingService.ParsedBet::selection)
                .contains("5001", "9849")
                .doesNotContain("4999", "5501");
        assertThat(parity).extracting(LotteryBettingService.ParsedBet::selection)
                .contains("1100", "9988")
                .doesNotContain("0000", "1111");
        assertThat(service.parse("单双各1", odds)).hasSize(2);
    }

    @Test
    void parsesFourCharacterFixedGroupsWithSharedAmount() {
        List<LotteryBettingService.ParsedCommand> commands = service.parseCommands(
                "123*\n*123\n7*89\n**78各1；", odds);

        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).items()).hasSize(4);
        assertThat(commands.get(0).items()).extracting(LotteryBettingService.ParsedBet::play)
                .containsExactly("三定位", "三定位", "三定位", "二定位");
        assertThat(commands.get(0).items()).extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("123X", "X123", "7X89", "XX78");
        assertThat(commands.get(0).items()).allSatisfy(bet ->
                assertThat(bet.amount()).isEqualByComparingTo("1"));
    }

    @Test
    void parsesPunctuationSeparatedFixedGroupsIncludingFourDigits() {
        assertThat(service.parse("123*.*123/7*89、**78，1234@5678各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("123X", "X123", "7X89", "XX78", "1234", "5678");
        assertThat(service.parse("1234/5678各1", odds))
                .extracting(LotteryBettingService.ParsedBet::play)
                .containsExactly("四定位", "四定位");
        assertThat(service.parse("1234各1", odds)).extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("1234");
    }

    @Test
    void parsesFixedGroupsWithSeparateAmountsAndPreservesDuplicates() {
        assertThat(service.parseCommands("7*89各1\n*123各1", odds)).hasSize(2);
        assertThat(service.parse("123*各1/123*各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("123X", "123X");
        assertThat(service.parse("1234各1@5678各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("1234", "5678");
        assertThat(service.parse("***7各1", odds)).extracting(LotteryBettingService.ParsedBet::play)
                .containsExactly("一定位");
        assertThatThrownBy(() -> service.parse("****各1", odds)).hasMessageContaining("无法识别");
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
    void parsesDocumentBareNumbersBeforeLegacySingleDigitShorthand() {
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
                .containsExactly("二字现", "12");
        assertThat(service.parse("123各1", odds).get(0))
                .extracting(LotteryBettingService.ParsedBet::play, LotteryBettingService.ParsedBet::selection)
                .containsExactly("三字现", "123");
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
        assertThat(service.parse("257全倒二定各10", odds))
                .hasSize(36)
                .containsExactlyElementsOf(service.parse("257倒二定各10", odds));
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
    void parsesReverseFixedAliasWithModeBeforePool() {
        assertThat(service.parse("二定全倒1579各1", odds))
                .containsExactlyElementsOf(service.parse("1579全倒二定各1", odds));
        assertThat(service.parse("三定全倒1579各1", odds))
                .containsExactlyElementsOf(service.parse("1579全倒三定各1", odds));
        assertThat(service.parse("四定全倒1579各1", odds))
                .hasSize(24)
                .containsExactlyElementsOf(service.parse("1579全倒四定各1", odds));
        assertThat(service.parse("四定倒1579各1", odds))
                .containsExactlyElementsOf(service.parse("1579倒四定各1", odds));
        assertThat(service.parse("四定全倒1123各1", odds))
                .hasSize(12)
                .containsExactlyElementsOf(service.parse("1123全倒四定各1", odds));
        assertThat(service.parseCommands("四定全倒1579各1", odds).get(0).content())
                .isEqualTo("四定全倒1579各1");
    }

    @Test
    void parsesImplicitFullReverseAsFourPositionMultiset() {
        String command = "1133557799全倒各1";
        List<LotteryBettingService.ParsedBet> bets = service.parse(command, odds);

        assertThat(bets).hasSize(540)
                .containsExactlyElementsOf(service.parse("1133557799倒四定各1", odds))
                .allSatisfy(bet -> {
                    assertThat(bet.play()).isEqualTo("四定位");
                    assertThat(bet.amount()).isEqualByComparingTo("1");
                    assertThat(bet.odds()).isEqualByComparingTo("9600");
                });
        assertThat(service.parseCommands(command, odds).get(0).content()).isEqualTo(command);
        LotteryBettingService.ParsedBet exactFour = bets.stream()
                .filter(bet -> "1133".equals(bet.selection())).findFirst().orElseThrow();
        assertThat(service.isWinning(exactFour, service.deriveDraw("11330"))).isTrue();
        assertThat(service.isWinning(exactFour, service.deriveDraw("11303"))).isFalse();
        assertThat(service.parse("1111全倒各1", odds)).hasSize(1);
        assertThat(service.parse("1234全倒各1", odds)).hasSize(24);
    }

    @Test
    void parsesReverseFixedAmountSlashAliasWithoutChangingBasicSlashSyntax() {
        Map<String, Integer> expectedSizes = Map.of(
                "一", 28,
                "二", 258,
                "三", 912,
                "四", 1_020);
        expectedSizes.forEach((count, expectedSize) -> {
            List<LotteryBettingService.ParsedBet> slash = service.parse("24567861倒" + count + "定/1", odds);
            List<LotteryBettingService.ParsedBet> canonical = service.parse("24567861倒" + count + "定各1", odds);

            assertThat(slash).hasSize(expectedSize).containsExactlyElementsOf(canonical)
                    .allSatisfy(bet -> {
                        assertThat(bet.play()).isEqualTo(count + "定位");
                        assertThat(bet.amount()).isEqualByComparingTo("1");
                    });
        });
        assertThat(service.parse("24567861三定倒/0.5", odds))
                .containsExactlyElementsOf(service.parse("24567861倒三定各0.5", odds));
        assertThat(service.parse("24567861倒三定/1\n24567861倒三定/1", odds)).hasSize(1_824);
        assertThat(service.splitCommandsForDisplay("24567861倒三定/1\n24567861倒三定/1"))
                .containsExactly("24567861倒三定/1", "24567861倒三定/1");

        assertThat(service.parse("123/10", odds).get(0).play()).isEqualTo("三字现");
        assertThat(service.parse("123定/10", odds).get(0).play()).isEqualTo("三定位");
    }

    @Test
    void parsesHistoricalFixedPoolAndReverseAliases() {
        assertThat(service.parse("0759三定各0.1", odds)).hasSize(96);
        assertThat(service.parse("56790三定各0.2", odds)).hasSize(240);
        assertThat(service.parse("5679倒两定各1", odds)).hasSize(72);
        assertThat(service.parse("6651倒各1", odds)).hasSize(12);
    }

    @Test
    void walkFixedUsesThePoolAfterWalkLikeTheReferenceSite() {
        List<LotteryBettingService.ParsedBet> walked = service.parse("12345走56789三定各1", odds);
        assertThat(walked).hasSize(240)
                .containsExactlyInAnyOrderElementsOf(service.parse("56789三定各1", odds));
        assertThat(walked).extracting(LotteryBettingService.ParsedBet::selection)
                .contains("567X", "X987")
                .allSatisfy(selection -> assertThat(selection).matches("[56789X]{4}"));
        assertThat(service.parse("98走123三定各1", odds)).hasSize(24)
                .containsExactlyInAnyOrderElementsOf(service.parse("123三定各1", odds));
        assertThat(service.parse("98走112三定各1", odds)).hasSize(12)
                .containsExactlyInAnyOrderElementsOf(service.parse("112三定各1", odds));
    }

    @Test
    void positionedWalkUsesRemainingPoolWithoutReplacement() {
        List<LotteryBettingService.ParsedBet> tail = service.parse("0413尾移9587三定各1", odds);
        assertThat(tail).hasSize(144)
                .allSatisfy(bet -> {
                    assertThat(bet.play()).isEqualTo("三定位");
                    assertThat(bet.amount()).isEqualByComparingTo("1");
                });
        assertThat(tail).extracting(LotteryBettingService.ParsedBet::selection)
                .contains("95X0", "X874")
                .doesNotContain("99X0", "11X0");
        assertThat(service.parse("0413尾取9587三定各1", odds))
                .containsExactlyElementsOf(tail);

        List<LotteryBettingService.ParsedBet> tens = service.parse("01234十走5678三定各1", odds);
        assertThat(tens).hasSize(180)
                .allSatisfy(bet -> assertThat(bet.play()).isEqualTo("三定位"));
        assertThat(tens).extracting(LotteryBettingService.ParsedBet::selection)
                .contains("560X", "7X48")
                .doesNotContain("550X", "000X");

        assertThat(service.parse("十01234移5678三定各1", odds)).hasSize(180);
        assertThat(service.parse("12十走112三定各1", odds)).hasSize(18);
        assertThat(service.parse("12十走3456二定各1", odds)).hasSize(24);
        assertThat(service.parse("12十走3456四定各1", odds)).hasSize(48);
    }

    @Test
    void malformedPositionedWalkIsRejectedInsteadOfExpandingUnrestrictedDigits() {
        assertThatThrownBy(() -> service.parse("01234十走三定各1", odds))
                .hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("01234十移56走78三定各1", odds))
                .hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("01234十走5三定各1", odds))
                .hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("123走456三定走789各1", odds))
                .hasMessageContaining("无法识别");
    }

    @Test
    void splitsCombinedCommandsAndPreservesDuplicateCommands() {
        assertThat(service.parse("5679各1.1 9765各1", odds)).hasSize(2);
        assertThat(service.parse("0759三定各1\n0759三定各1", odds)).hasSize(192);
        assertThat(service.parse("千01234百01234十01234尾01234各1\n千01234百01234十01234尾01234各1", odds))
                .hasSize(1_250);
        assertThat(service.splitCommandsForDisplay("0759三定各1\n0759三定各1"))
                .containsExactly("0759三定各1", "0759三定各1");
        assertThat(service.parse("0759三定各1,0759三定各1", odds)).hasSize(192);
        assertThat(service.splitCommandsForDisplay("0759三定各1,0759三定各1"))
                .containsExactly("0759三定各1", "0759三定各1");
    }

    @Test
    void matchesHistoricalFourPositionFilterCounts() {
        assertThat(List.of(
                service.parse("除个4四定取千百十个合023697458百十个合623957104两数合0268除对数49除单单大双除四兄弟除四重除两双重各1", odds).size(),
                service.parse("除个7四定取千百合473018652百个合237460518三数合01258除对数38各0.5", odds).size(),
                service.parse("配01234配56789四定除千百十个合4三数合54321除单双双双取两兄弟各1.6", odds).size(),
                service.parse("四定千123457906百123567890十123457890取千百十个合123456789取千百个合012345689除三重除四重除四兄弟除对数371各0.8", odds).size(),
                service.parse("千百十尾23457890除大大大大除小小小小除单单单单除双双双双除千百十个合16各0.1", odds).size()))
                .containsExactly(6_146, 6_612, 4_876, 5_550, 2_590);
    }

    @Test
    void appliesSinglePositionSumAndBareOppositePairFilters() {
        String command = "四定取千百个合079654123千百十个合078965421"
                + "千十个合078965421个合079654123两数合246含0378取对数各0.1";
        List<LotteryBettingService.ParsedBet> bets = service.parse(command, odds);

        assertThat(bets).hasSize(1_446)
                .allSatisfy(bet -> {
                    assertThat(bet.play()).isEqualTo("四定位");
                    assertThat(bet.amount()).isEqualByComparingTo("0.1");
                    assertThat(bet.selection().charAt(3)).isNotEqualTo('8');
                    assertThat(hasOppositePair(bet.selection())).isTrue();
                });
        assertThat(service.parse("四定取个合079654123各1", odds)).hasSize(9_000);
        assertThat(service.parse("取个合079654123各1", odds)).hasSize(9);
        assertThat(service.parse("四定取对数各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .contains("0005").doesNotContain("0000", "0123");
        assertThat(service.parse("四定除对数各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .contains("0000", "0123").doesNotContain("0005");
    }

    private boolean hasOppositePair(String value) {
        for (int first = 0; first + 1 < value.length(); first++) {
            if (Math.abs(value.charAt(first) - value.charAt(first + 1)) == 5) return true;
        }
        return false;
    }

    @Test
    void excludesFourSmallNumbersInCompoundPositionCommand() {
        String command = "千523167890百128456790十163245890个124567390"
                + "千百十合241567890取千百十个合234567890两数合2846三数合124560"
                + "含234789除四小除对数18除值21值21除单单单双除大大小单除三兄弟各1.1";
        List<LotteryBettingService.ParsedBet> bets = service.parse(command, odds);

        assertThat(bets).hasSize(3_088);
        assertThat(service.parse("四定取四小各1", odds)).hasSize(625);
        assertThat(service.parse("四定除四小各1", odds)).hasSize(9_375);
        assertThat(service.parse("四定取四大各1", odds)).hasSize(625);
    }

    @Test
    void fivePositionTwoUpperPrizeDoesNotCountTheConstrainedFifthDigit() {
        List<LotteryBettingService.ParsedBet> bets = service.parse("五369五位二定上奖3569各15", odds);

        assertThat(bets).hasSize(12);
        assertThat(bets).allSatisfy(bet -> {
            assertThat(bet.play()).isEqualTo("五位二定");
            assertThat(bet.selection()).hasSize(5);
            assertThat("369").contains(String.valueOf(bet.selection().charAt(4)));
            assertThat("3569").contains(String.valueOf(bet.selection().replace("X", "").charAt(0)));
        });
    }

    @Test
    void doesNotTurnVisualFiltersIntoStandaloneBigSmallBets() {
        List<LotteryBettingService.ParsedBet> bets = service.parse(
                "112233445566778009倒四定配023578配234578千百合123456789两数合0248三数合234567含23456"
                        + "除大大小双除单单单大除三重除值19值19除四兄弟除大大大小除小小小大各0.3", odds);

        assertThat(bets).isNotEmpty().allSatisfy(bet -> {
            assertThat(bet.play()).isEqualTo("四定位");
            assertThat(bet.selection()).matches("[0-9X]{4}");
        });
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
                .collect(java.util.stream.Collectors.joining("\n"));

        assertThat(service.splitCommandsForDisplay(content)).hasSize(14);
        assertThat(service.parse(content, odds)).hasSize(74_871);
        assertThat(service.parseCommands(content, odds))
                .hasSize(14)
                .allSatisfy(command -> assertThat(command.content()).startsWith("13680配"));
        assertThat(service.parseCommands(content, odds).stream().mapToInt(command -> command.items().size()).sum())
                .isEqualTo(74_871);
    }

    @Test
    void matchesRemainingHistoricalCorpusCounts() {
        assertThat(service.parse(
                "089613三定各0.3\n7890三定各0.3\n8902三定各0.3\n0136三定各0.3\n1234三定各0.3\n0123三定各0.3", odds)).hasSize(960);
        assertThat(service.parse("1133445599778800倒四定各1.3", odds)).hasSize(3_864);
        assertThat(service.parse(
                "123456780头123467890百234567890十。两数合234567除三重除三兄弟各7", odds)).hasSize(650);
        assertThat(service.parse(
                "123456780头123467890百234567890十012346789个。两数合012345除三重各0.5", odds)).hasSize(6_264);
        assertThat(service.parse("12364790头12307698尾除双重各65", odds)).hasSize(57);
        assertThat(service.parse(
                "6516各8.8 6156各8.8\n6651倒各0.6\n5561倒各0.6\n1156倒各0.6", odds)).hasSize(38);
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
                .containsExactly("11XX", "12XX");
        assertThat(service.parse("三现全倒112各1", odds)).hasSize(1);
        assertThat(service.parse("五位二定千12五34各1", odds)).hasSize(4);
        assertThat(service.parse("千12五34五位二定各1", odds)).hasSize(4);
        assertThat(service.parse("千12五位二定五34各1", odds)).hasSize(20);
        assertThat(service.parse("除12配34配二定各1", odds)).hasSize(552);
    }

    @Test
    void upperPrizeMatchesAnyUnspecifiedPositionLikeTheReferenceSite() {
        List<LotteryBettingService.ParsedBet> prefixed = service.parse("1上奖四定各1", odds);
        List<LotteryBettingService.ParsedBet> suffixed = service.parse("四定上奖1各1", odds);

        assertThat(prefixed).hasSize(3439);
        assertThat(suffixed).hasSize(3439);
        assertThat(prefixed).extracting(LotteryBettingService.ParsedBet::selection)
                .contains("1111", "7419").doesNotContain("0000", "9876");
        assertThat(prefixed).extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactlyElementsOf(suffixed.stream().map(LotteryBettingService.ParsedBet::selection).toList());
    }

    @Test
    void supportsLegacyQuickPickCurrentPoolsAndComplexPool() {
        assertThat(service.parse("12配34二现各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("13", "14", "23", "24");
        assertThat(service.parse("除12配34二现各1", odds)).hasSize(51);
        assertThat(service.parse("二现复式12各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("11", "12", "22");
    }

    @Test
    void supportsLegacyQuickPickPositionPoolsAndFifthBallPairs() {
        assertThatThrownBy(() -> service.parse("千1百2十3个4二定各1", odds))
                .hasMessageContaining("无法识别");
        assertThat(service.parse("百12五34五位二定各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("X1XX3", "X1XX4", "X2XX3", "X2XX4");
        assertThat(service.parse("千1百2十3个4五5五位二定各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("1XXX5", "X2XX5", "XX3X5", "XXX45");
    }

    @Test
    void supportsLegacyQuickPickRotatedFixedPools() {
        assertThat(service.parse("配12配3二定各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly(
                        "13XX", "1X3X", "1XX3", "23XX", "2X3X", "2XX3",
                        "31XX", "32XX", "3X1X", "3X2X", "3XX1", "3XX2",
                        "X13X", "X1X3", "X23X", "X2X3", "X31X", "X32X",
                        "X3X1", "X3X2", "XX13", "XX23", "XX31", "XX32");
        assertThat(service.parse("除配12配3二定各1", odds)).hasSize(576);
        assertThat(service.parse("配1配2配3三定各1", odds)).hasSize(24);
        assertThat(service.parse("配1配2配3配4四定各1", odds)).hasSize(24);
    }

    @Test
    void playNameBeforeRotatedPoolsUsesTheSameDistinctPositionMatching() {
        String firstFilters = "两数合15三数合1234含1368除对数05除四兄弟各1";
        List<LotteryBettingService.ParsedBet> first = service.parse(
                "四定配258369配258369" + firstFilters, odds);
        List<LotteryBettingService.ParsedBet> firstReordered = service.parse(
                "配258369配258369四定" + firstFilters, odds);

        String secondFilters = "两数合468三数合7890含1368除对数49除小小小大除四兄弟各1";
        List<LotteryBettingService.ParsedBet> second = service.parse(
                "四定配1234567配1234567" + secondFilters, odds);
        List<LotteryBettingService.ParsedBet> secondReordered = service.parse(
                "配1234567配1234567四定" + secondFilters, odds);
        assertThat(List.of(first.size(), second.size())).containsExactly(4_241, 5_856);
        assertThat(first).containsExactlyElementsOf(firstReordered);
        assertThat(second).containsExactlyElementsOf(secondReordered);
    }

    @Test
    void supportsTakeRotatedPoolsAndValueRangeWithoutChangingExcludePools() {
        assertThat(service.parse("取配1配2二定各1", odds))
                .containsExactlyElementsOf(service.parse("配1配2二定各1", odds));
        assertThat(service.parse("取配1配1四定各1", odds)).hasSize(523);
        assertThat(service.parse("除配12配3二定各1", odds)).hasSize(576);
        assertThat(service.parse("四定值范围8-28各1", odds))
                .containsExactlyElementsOf(service.parse("四定取值8值28各1", odds));
        assertThat(service.parse("四定除值范围8-28各1", odds))
                .containsExactlyElementsOf(service.parse("四定除值8值28各1", odds));
    }

    @Test
    void bareValueEndpointsDefaultToTakeAfterOtherExcludeFilters() {
        String prefix = "四定含0218两数合0213三数合1234除千百十个合8除三兄弟除单单大大";
        assertThat(service.parse(prefix + "值9值25各0.1", odds))
                .hasSize(4_792)
                .containsExactlyElementsOf(service.parse(prefix + "取值9值25各0.1", odds));
        assertThat(service.parse("四定值9值25各1", odds))
                .containsExactlyElementsOf(service.parse("四定取值9值25各1", odds));
        assertThat(service.parse("四定除值9值25各1", odds))
                .containsExactlyElementsOf(service.parse("四定除值范围9-25各1", odds));
    }

    @Test
    void bareSiblingFilterMeansTwoSiblingsInCombinedCommand() {
        String prefix = "除2配2四定除千百十个合1两数合0246三数合56789";
        String suffix = "除三兄弟值范围7-29排除39除两对数各1";
        assertThat(service.parse(prefix + "取兄弟" + suffix, odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactlyElementsOf(service.parse(prefix + "取两兄弟" + suffix, odds)
                        .stream().map(LotteryBettingService.ParsedBet::selection).toList());
        assertThat(service.parse(prefix + "取兄弟" + suffix, odds)).hasSize(1_552);
    }

    @Test
    void twoOppositePairsRequireTwoDisjointPositionPairs() {
        assertThat(service.parse("四定取两对数各1", odds)).hasSize(100)
                .extracting(LotteryBettingService.ParsedBet::selection)
                .contains("0505", "0516").doesNotContain("0511", "0005");
        assertThat(service.parse("四定除两对数各1", odds)).hasSize(9_900);
        assertThat(service.parse("四定取二对数各1", odds))
                .containsExactlyElementsOf(service.parse("四定取两对数各1", odds));
    }

    @Test
    void specifiedOppositeGroupsRequireExactAdjacentSequencesAndTakeGroupsAreAlternatives() {
        assertThat(service.parse("二定取对数05取对数16各1", odds)).hasSize(6)
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactlyInAnyOrder("05XX", "X05X", "XX05", "16XX", "X16X", "XX16")
                .doesNotContain("0X5X", "50XX", "61XX");
        assertThat(service.parse("三定取对数05取对数16各1", odds)).hasSize(120)
                .extracting(LotteryBettingService.ParsedBet::selection)
                .contains("050X", "X051", "160X", "X161")
                .doesNotContain("0X51", "50X1", "61X0");
        assertThat(service.parse("五位二定取对数05取对数16各1", odds)).hasSize(2)
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactlyInAnyOrder("XXX05", "XXX16");
        assertThat(service.parse("二定除对数05除对数16各1", odds)).hasSize(594)
                .extracting(LotteryBettingService.ParsedBet::selection)
                .doesNotContain("05XX", "X05X", "XX05", "16XX", "X16X", "XX16")
                .contains("0X5X", "50XX", "61XX");
    }

    @Test
    void supportsPostfixOppositePairAliasesAndRejectsUnparsedPairText() {
        assertThat(service.parse("四定16对各1", odds)).hasSize(299)
                .containsExactlyElementsOf(service.parse("四定取对数16各1", odds));
        assertThat(service.parse("四定取16对各1", odds))
                .containsExactlyElementsOf(service.parse("四定取对数16各1", odds));
        assertThat(service.parse("四定除38对各1", odds)).hasSize(9_701)
                .containsExactlyElementsOf(service.parse("四定除对数38各1", odds));

        assertThatThrownBy(() -> service.parse("四定1对各1", odds)).hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("四定对各1", odds)).hasMessageContaining("无法识别");
        assertThatThrownBy(() -> service.parse("四定16对错误各1", odds)).hasMessageContaining("无法识别");
    }

    @Test
    void matchesReferenceCombinedCommandWithOppositePairsAndSiblings() {
        String prefix = "除2配2四定除千百十个合1两数合0246三数合56789";
        String command = prefix + "值范围7-29排除39除两对数各0.3\n"
                + prefix + "除三兄弟值范围7-28排除39除两对数各0.5\n"
                + prefix + "取兄弟除三兄弟值范围7-29排除39除两对数各1";
        List<LotteryBettingService.ParsedCommand> parsed = service.parseCommands(command, odds);
        assertThat(parsed).extracting(item -> item.items().size()).containsExactly(3_024, 2_568, 1_552);
        assertThat(parsed.stream().mapToInt(item -> item.items().size()).sum()).isEqualTo(7_144);
        assertThat(parsed.stream().flatMap(item -> item.items().stream())
                .map(LotteryBettingService.ParsedBet::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("3743.2");
    }

    @Test
    void splitsConsecutiveFilteredCommandsAtEveryAmountMarker() {
        String prefix = "112233445667788990全倒四定含1258两数合4680三数合23456"
                + "千百合123567890千百十个合123456789";
        String first = prefix + "取两兄弟除对数05各0.2";
        String second = prefix + "取两兄弟除三兄弟除对数05各0.2";
        String third = prefix + "除三兄弟除对数05各0.2";
        List<String> expected = List.of(first, second, third);

        for (String separator : List.of(",", "\n", "")) {
            List<LotteryBettingService.ParsedCommand> parsed = service.parseCommands(
                    String.join(separator, expected), odds);
            assertThat(parsed).extracting(LotteryBettingService.ParsedCommand::content)
                    .containsExactlyElementsOf(expected);
            assertThat(parsed).extracting(item -> item.items().size())
                    .containsExactly(4_240, 3_010, 4_321);
            assertThat(parsed.stream().mapToInt(item -> item.items().size()).sum()).isEqualTo(11_571);
            assertThat(parsed.stream().flatMap(item -> item.items().stream())
                    .map(LotteryBettingService.ParsedBet::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("2314.2");
        }

        List<String> leadingDecimalAmounts = List.of(
                first,
                prefix + "取两兄弟除三兄弟除对数05各.2",
                prefix + "除三兄弟除对数05各.2");
        List<LotteryBettingService.ParsedCommand> shorthandParsed = service.parseCommands(
                String.join(",", leadingDecimalAmounts), odds);
        assertThat(shorthandParsed).extracting(LotteryBettingService.ParsedCommand::content)
                .containsExactlyElementsOf(leadingDecimalAmounts);
        assertThat(shorthandParsed).extracting(item -> item.items().size())
                .containsExactly(4_240, 3_010, 4_321);
        assertThat(shorthandParsed.stream().flatMap(item -> item.items().stream()))
                .allSatisfy(item -> assertThat(item.amount()).isEqualByComparingTo("0.2"));

        assertThat(service.parseCommands(first + "," + first, odds))
                .extracting(LotteryBettingService.ParsedCommand::content)
                .containsExactly(first, first);
        assertThatThrownBy(() -> service.parseCommands(first + ",错误", odds))
                .hasMessageContaining("无法识别");
    }

    @Test
    void attributeXRequiresAnUnfilledPositionAndMatchesReferenceCommandCount() {
        assertThat(service.parse("三定取大X大大各1", odds)).hasSize(125);
        assertThat(service.parse("三定除大X大大各1", odds)).hasSize(3_875);
        assertThat(service.parse("四定除大X大大各1", odds)).hasSize(10_000);

        String command = "取配124679配013589四定取千百合56789百十合56789十个合01342"
                + "千百十个合123456790两数合180三数合0258除四兄弟双双重"
                + "值范围8-28取含23456除大X大大除单双大小各0.1";
        assertThat(service.parse(command, odds)).hasSize(612)
                .allSatisfy(item -> {
                    assertThat(item.play()).isEqualTo("四定位");
                    assertThat(item.amount()).isEqualByComparingTo("0.1");
                });
    }

    @Test
    void supportsLegacyQuickPickFifthBallGenerationAndFilters() {
        assertThat(service.parse("五位二定复式12各1", odds)).hasSize(16)
                .allSatisfy(item -> assertThat(item.odds()).isEqualByComparingTo("88"));
        assertThat(service.parse("12倒五位二定各1", odds)).hasSize(8);
        assertThat(service.parse("五位二定除全转12各1", odds)).hasSize(392);
        assertThat(service.parse("配1配2五位二定各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("1XXX2", "2XXX1", "X1XX2", "X2XX1",
                        "XX1X2", "XX2X1", "XXX12", "XXX21");
        assertThat(service.parse("除配1配2五位二定各1", odds)).hasSize(392);
        assertThat(service.parse("五位二定取双重各1", odds)).hasSize(40);
        assertThat(service.parse("五位二定取千五合5各1", odds)).hasSize(10);
    }

    @Test
    void fifthPositionTwoFallsBackToTwoPositionOddsForExistingOwners() {
        List<OddDO> legacyOdds = odds.stream().filter(item -> !"regex5d2".equals(item.getCode())).toList();

        assertThat(service.parse("五位二定千1五2各1", legacyOdds).get(0).odds())
                .isEqualByComparingTo("96");
    }

    @Test
    void quickPickPreviewIgnoresAmountLimitsButKeepsPlayAvailability() {
        OddDO limited = odd("regex2d", "二定位", "96");
        limited.setMinLimit(new BigDecimal("100"));

        assertThatThrownBy(() -> service.parse("千1百2二定各1", List.of(limited)))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.parsePreview("千1百2二定各1", List.of(limited))).hasSize(1);

        limited.setStatus("停用");
        assertThatThrownBy(() -> service.parsePreview("千1百2二定各1", List.of(limited)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void supportsLegacyQuickPickComplexAndVisualFilters() {
        assertThat(service.parse("四定复式12各1", odds)).hasSize(16);
        assertThat(service.parse("四定复式12除含1各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("2222");
        assertThat(service.parse("四定复式01取X单双X各1", odds)).hasSize(4);
        assertThat(service.parse("千1百2二定取乘号位置十个各1", odds))
                .extracting(LotteryBettingService.ParsedBet::selection)
                .containsExactly("12XX");
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
        LotteryBettingService.ParsedBet regular = service.parse("1***各1", odds).get(0);
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
