package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.OddDO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.BET_CONTENT_INVALID;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.BET_LIMIT_INVALID;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.PLAY_TYPE_DISABLED;

@Service
public class LotteryBettingService {

    private static final String DIGITS = "0123456789";
    private static final int SETTLEMENT_BALL_COUNT = 4;
    private static final int MAX_SINGLE_COMMAND_ITEMS = 10_000;
    private static final int MAX_COMBINED_COMMAND_ITEMS = 100_000;
    private static final String FIVE_POSITION_TWO_ODD_CODE = "regex5d2";
    private static final String POSITION_LABELS = "头千百十尾个五";
    private static final Map<Character, Integer> POSITION_INDEX = Map.of(
            '头', 0, '千', 0, '百', 1, '十', 2, '尾', 3, '个', 3, '五', 4);
    private static final Map<Character, Integer> CHINESE_COUNT = Map.of('一', 1, '二', 2, '三', 3, '四', 4);

    public record ParsedBet(String play, String selection, BigDecimal amount, BigDecimal odds) {
    }

    public record DrawResult(String result, List<Integer> digits, String bigSmall, String oddEven,
                             String dragonTiger) {
    }

    public List<ParsedBet> parse(String rawContent, List<OddDO> odds) {
        List<String> commands = splitCommands(rawContent);
        List<ParsedBet> result = new ArrayList<>();
        for (String command : commands) {
            result.addAll(parseSingle(command, odds));
            if (result.size() > MAX_COMBINED_COMMAND_ITEMS) {
                throw exception(BET_LIMIT_INVALID);
            }
        }
        return result;
    }

    /**
     * Expands a quick-pick preview with the owner's enabled plays and rates, but without applying the
     * final per-bet amount limits. The real room submission always calls {@link #parse(String, List)}
     * again with the configured limits intact.
     */
    public List<ParsedBet> parsePreview(String rawContent, List<OddDO> odds) {
        List<OddDO> previewOdds = odds.stream().map(source -> {
            OddDO target = new OddDO();
            target.setCode(source.getCode());
            target.setPlay(source.getPlay());
            target.setItem(source.getItem());
            target.setRate(source.getRate());
            target.setSecondaryRate(source.getSecondaryRate());
            target.setStatus(source.getStatus());
            return target;
        }).toList();
        return parse(rawContent, previewOdds);
    }

    /**
     * Splits a combined room command for display without changing its text or removing duplicate subcommands.
     */
    public List<String> splitCommandsForDisplay(String rawContent) {
        return List.copyOf(splitCommands(rawContent));
    }

    private List<ParsedBet> parseSingle(String rawContent, List<OddDO> odds) {
        String content = expandNumericFixedShorthand(normalizeReverseFixedAlias(
                normalizeReferenceAmountAlias(normalize(rawContent))));
        if (content.isBlank()) {
            throw exception(BET_CONTENT_INVALID);
        }
        List<IndexedBet> values = new ArrayList<>();
        values.addAll(parseReference(content, odds));

        Matcher groupMatcher = Pattern.compile("([大小单双龙虎和]{2,})各(\\d+(?:\\.\\d+)?)").matcher(content);
        while (groupMatcher.find()) {
            BigDecimal amount = amount(groupMatcher.group(2));
            for (char selection : groupMatcher.group(1).toCharArray()) {
                values.add(new IndexedBet(groupMatcher.start(), basicBet(String.valueOf(selection), amount, odds)));
            }
        }

        Matcher basicMatcher = Pattern.compile("(大|小|单|双|龙|虎|和)(\\d+(?:\\.\\d+)?)").matcher(content);
        while (basicMatcher.find()) {
            values.add(new IndexedBet(basicMatcher.start(), basicBet(basicMatcher.group(1), amount(basicMatcher.group(2)), odds)));
        }

        Matcher fixedMatcher = Pattern.compile("(\\d{1,4})定(?:位)?(?:=|/|:)(\\d+(?:\\.\\d+)?)").matcher(content);
        while (fixedMatcher.find()) {
            String selection = fixedMatcher.group(1);
            int count = selection.length();
            values.add(new IndexedBet(fixedMatcher.start(), configuredBet(chineseCount(count) + "定位", selection,
                    amount(fixedMatcher.group(2)), "regex" + count + "d", odds)));
        }

        Matcher numberMatcher = Pattern.compile("(?:号码|号|现)?(\\d{1,4})(?:现)?(?:=|/|:)(\\d+(?:\\.\\d+)?)").matcher(content);
        while (numberMatcher.find()) {
            String selection = numberMatcher.group(1);
            boolean fourSame = selection.length() == 4 && selection.chars().distinct().count() == 1;
            String play = fourSame ? "四条" : chineseCount(selection.length()) + "字现";
            String code = fourSame ? "regex4d4" : switch (selection.length()) {
                case 1 -> "regex1d";
                case 2 -> "regex2x";
                case 3 -> "regex3x";
                default -> "regex4x";
            };
            values.add(new IndexedBet(numberMatcher.start(), configuredBet(play, selection,
                    amount(numberMatcher.group(2)), code, odds)));
        }

        Map<String, IndexedBet> unique = new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparingInt(IndexedBet::index)).forEach(item -> {
            ParsedBet bet = item.bet();
            unique.put(item.index() + ":" + bet.play() + ":" + bet.selection() + ":" + bet.amount(), item);
        });
        List<ParsedBet> result = unique.values().stream().map(IndexedBet::bet).toList();
        if (result.isEmpty()) {
            throw exception(BET_CONTENT_INVALID);
        }
        if (result.size() > MAX_SINGLE_COMMAND_ITEMS) {
            throw exception(BET_LIMIT_INVALID);
        }
        return result;
    }

    public DrawResult deriveDraw(String rawResult) {
        List<Integer> digits = rawResult.chars().filter(Character::isDigit).map(value -> value - '0').boxed().toList();
        if (digits.size() < 3 || digits.size() > 5) {
            throw exception(BET_CONTENT_INVALID);
        }
        List<Integer> settlementDigits = digits.stream().limit(SETTLEMENT_BALL_COUNT).toList();
        int sum = settlementDigits.stream().mapToInt(Integer::intValue).sum();
        int threshold = (int) Math.ceil(settlementDigits.size() * 4.5);
        int first = settlementDigits.get(0);
        int last = settlementDigits.get(settlementDigits.size() - 1);
        return new DrawResult(digits.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""),
                digits, sum >= threshold ? "大" : "小", sum % 2 == 0 ? "双" : "单",
                first == last ? "和" : first > last ? "龙" : "虎");
    }

    public boolean isWinning(ParsedBet bet, DrawResult draw) {
        if ("大小".equals(bet.play())) return bet.selection().equals(draw.bigSmall());
        if ("单双".equals(bet.play())) return bet.selection().equals(draw.oddEven());
        if ("龙虎".equals(bet.play())) return bet.selection().equals(draw.dragonTiger());
        String digits = draw.digits().stream().limit(SETTLEMENT_BALL_COUNT)
                .map(String::valueOf).reduce("", String::concat);
        if (bet.play().endsWith("定位") || "五位二定".equals(bet.play())) {
            if (bet.selection().contains("X")) {
                if (digits.length() != bet.selection().length()) return false;
                for (int i = 0; i < digits.length(); i++) {
                    char expected = bet.selection().charAt(i);
                    if (expected != 'X' && expected != digits.charAt(i)) return false;
                }
                return true;
            }
            return digits.startsWith(bet.selection());
        }
        if ("四条".equals(bet.play())) {
            return digits.chars().filter(value -> value == bet.selection().charAt(0)).count() >= 4;
        }
        if (bet.play().endsWith("字现")) {
            List<Character> remaining = digits.chars().mapToObj(value -> (char) value).collect(java.util.stream.Collectors.toList());
            for (char value : bet.selection().toCharArray()) {
                if (!remaining.remove(Character.valueOf(value))) return false;
            }
            return true;
        }
        return false;
    }

    private List<IndexedBet> parseReference(String content, List<OddDO> odds) {
        Matcher amountMatcher = Pattern.compile("各(\\d+(?:\\.\\d+)?)$").matcher(content);
        if (!amountMatcher.find()) return List.of();
        String expression = content.substring(0, amountMatcher.start());
        if (!Pattern.compile("[头千百十尾个倒定合含除取现配]").matcher(expression).find()) return List.of();
        BigDecimal amount = amount(amountMatcher.group(1));

        if (expression.contains("五位二定")) {
            OddDO odd = configuredOdd(FIVE_POSITION_TWO_ODD_CODE, amount, odds);
            int modeAt = expression.indexOf("五位二定");
            String before = expression.substring(0, modeAt);
            String after = expression.substring(modeAt + "五位二定".length());
            String source = before + after;
            Matcher complex = Pattern.compile("复式([0-9]+)").matcher(source);
            Matcher reverse = Pattern.compile("([0-9]+)(?:全)?倒").matcher(source);
            Matcher excludedReverse = Pattern.compile("除全转([0-9]+)").matcher(source);
            RotatedFixed rotatedFixed = extractRotatedFiveTwo(expression);
            List<String> selections;
            if (rotatedFixed != null) {
                selections = applyRotatedFixedFilter(fullFiveTwoSpace(chars(DIGITS)), rotatedFixed);
            } else if (complex.find()) {
                selections = fullFiveTwoSpace(chars(complex.group(1)));
            } else if (reverse.find()) {
                selections = reverseFixedSpace(reverse.group(1), 2, 5, true);
            } else if (excludedReverse.find()) {
                selections = fullFiveTwoSpace(chars(DIGITS));
            } else {
                // Preserve the legacy word order: once the mode token follows a positioned pool,
                // trailing text is filter text rather than another position declaration.
                String groupSource = modeAt > 0 && Pattern.compile("[千百十个五]").matcher(before).find()
                        ? before : source;
                Map<Integer, Set<Character>> groups = extractFivePositionGroups(groupSource);
                selections = groups.isEmpty()
                        ? fullFiveTwoSpace(chars(DIGITS))
                        : expandFiveTwoPositionExpression(groups);
            }
            selections = applyFilters(selections, expression);
            if (selections.isEmpty()) throw exception(BET_CONTENT_INVALID);
            return indexed(new ArrayList<>(new java.util.TreeSet<>(selections)), "五位二定",
                    amount, odd.getRate(), content.length());
        }

        Matcher currentMatcher = Pattern.compile("([二三四])(?:字)?现").matcher(expression);
        if (currentMatcher.find()) {
            int count = CHINESE_COUNT.get(currentMatcher.group(1).charAt(0));
            String code = "regex" + count + "x";
            OddDO odd = configuredOdd(code, amount, odds);
            List<String> selections = currentBaseSelections(expression, currentMatcher, count);
            Matcher fullReverse = Pattern.compile("全倒([0-9]+)").matcher(expression);
            if (fullReverse.find()) {
                List<String> arrangements = new ArrayList<>();
                multisetArrangements(fullReverse.group(1), count, new StringBuilder(),
                        new boolean[fullReverse.group(1).length()], arrangements);
                Set<String> allowed = new HashSet<>();
                for (String arrangement : arrangements) {
                    char[] digits = arrangement.toCharArray();
                    Arrays.sort(digits);
                    allowed.add(new String(digits));
                }
                selections.removeIf(selection -> !allowed.contains(selection));
            }
            selections = applyFilters(selections, expression);
            return indexed(selections, chineseCount(count) + "字现", amount, odd.getRate(), content.length());
        }

        Matcher fixedComplex = Pattern.compile("([二三四])(?:字)?定复式([0-9]+)").matcher(expression);
        if (fixedComplex.find()) {
            int count = CHINESE_COUNT.get(fixedComplex.group(1).charAt(0));
            OddDO odd = configuredOdd("regex" + count + "d", amount, odds);
            List<String> selections = fullFixedSpace(count, 4, chars(fixedComplex.group(2)));
            selections = applyFilters(selections, expression);
            if (selections.isEmpty()) throw exception(BET_CONTENT_INVALID);
            return indexed(new ArrayList<>(new java.util.TreeSet<>(selections)), chineseCount(count) + "定位",
                    amount, odd.getRate(), content.length());
        }

        RotatedFixed rotatedFixed = extractRotatedFixed(expression);

        Matcher reverseMatcher = Pattern.compile("([0-9]+)(?:全)?倒([一二三四])定").matcher(expression);
        if (reverseMatcher.find()) {
            int count = CHINESE_COUNT.get(reverseMatcher.group(2).charAt(0));
            OddDO odd = configuredOdd("regex" + count + "d", amount, odds);
            List<String> arrangements = new ArrayList<>();
            multisetArrangements(reverseMatcher.group(1), count, new StringBuilder(), new boolean[reverseMatcher.group(1).length()], arrangements);
            Set<String> values = new LinkedHashSet<>();
            for (List<Integer> positions : choosePositions(count)) {
                for (String arrangement : arrangements) {
                    char[] pattern = "XXXX".toCharArray();
                    for (int i = 0; i < positions.size(); i++) pattern[positions.get(i)] = arrangement.charAt(i);
                    values.add(new String(pattern));
                }
            }
            return indexed(applyFilters(new ArrayList<>(values), expression), chineseCount(count) + "定位",
                    amount, odd.getRate(), content.length());
        }

        Matcher poolMatcher = Pattern.compile("^([0-9]+)([二三四])定").matcher(expression);
        if (poolMatcher.find()) {
            int count = CHINESE_COUNT.get(poolMatcher.group(2).charAt(0));
            OddDO odd = configuredOdd("regex" + count + "d", amount, odds);
            List<String> arrangements = new ArrayList<>();
            multisetArrangements(poolMatcher.group(1), count, new StringBuilder(),
                    new boolean[poolMatcher.group(1).length()], arrangements);
            Set<String> values = new LinkedHashSet<>();
            for (List<Integer> positions : choosePositions(count)) {
                for (String arrangement : arrangements) {
                    char[] pattern = "XXXX".toCharArray();
                    for (int i = 0; i < positions.size(); i++) pattern[positions.get(i)] = arrangement.charAt(i);
                    values.add(new String(pattern));
                }
            }
            return indexed(applyFilters(new ArrayList<>(values), expression), chineseCount(count) + "定位",
                    amount, odd.getRate(), content.length());
        }

        int count = rotatedFixed == null ? detectFixedCount(expression) : rotatedFixed.count();
        OddDO odd = configuredOdd("regex" + count + "d", amount, odds);
        List<String> selections = rotatedFixed == null
                ? expandPositionExpression(expression, count)
                : fullFixedSpace(count);
        selections = applyFilters(selections, expression);
        if (rotatedFixed != null) selections = applyRotatedFixedFilter(selections, rotatedFixed);
        if (selections.isEmpty()) throw exception(BET_CONTENT_INVALID);
        return indexed(new ArrayList<>(new java.util.TreeSet<>(selections)), chineseCount(count) + "定位",
                amount, odd.getRate(), content.length());
    }

    private List<String> expandPositionExpression(String expression, int count) {
        String base = expression.split("。")[0].split("(?:除|含|取|上奖)")[0];
        Map<Integer, Set<Character>> groups = extractPositionGroups(base);
        if (groups.isEmpty() && explicitFixedCount(expression) != null) {
            return fullFixedSpace(count);
        }
        if (groups.isEmpty() && expression.contains("合")) {
            return completeSumSpace(expression);
        }
        if (groups.isEmpty()) throw exception(BET_CONTENT_INVALID);
        if (groups.size() >= count) {
            List<String> result = new ArrayList<>();
            for (List<Integer> selected : combinations(groups.keySet().stream().sorted().toList(), count)) {
                Map<Integer, Set<Character>> selectedGroups = new HashMap<>();
                selected.forEach(position -> selectedGroups.put(position, groups.get(position)));
                expandCartesian(selected, selectedGroups, 0, "XXXX".toCharArray(), result);
            }
            return result;
        }
        List<Integer> missingPositions = new ArrayList<>(List.of(0, 1, 2, 3));
        missingPositions.removeAll(groups.keySet());
        int missing = count - groups.size();
        if (missing < 0) throw exception(BET_CONTENT_INVALID);
        List<List<Integer>> openCombinations = combinations(missingPositions, missing);
        if (openCombinations.isEmpty() && missing == 0) openCombinations = List.of(List.of());
        List<String> result = new ArrayList<>();
        for (List<Integer> open : openCombinations) {
            Map<Integer, Set<Character>> all = new HashMap<>(groups);
            for (Integer position : open) all.put(position, new LinkedHashSet<>(DIGITS.chars().mapToObj(v -> (char) v).toList()));
            expandCartesian(new ArrayList<>(all.keySet().stream().sorted().toList()), all, 0, "XXXX".toCharArray(), result);
        }
        return result;
    }

    private List<String> completeSumSpace(String expression) {
        Set<Integer> positions = new LinkedHashSet<>();
        Matcher sum = Pattern.compile("((?:头|千|百|十|尾|个){2,4})合").matcher(expression);
        while (sum.find()) {
            positions.addAll(positionIndexes(sum.group(1)));
        }
        if (positions.isEmpty()) throw exception(BET_CONTENT_INVALID);
        Map<Integer, Set<Character>> groups = new HashMap<>();
        Set<Character> digits = new LinkedHashSet<>(DIGITS.chars().mapToObj(value -> (char) value).toList());
        positions.forEach(position -> groups.put(position, digits));
        List<String> result = new ArrayList<>();
        expandCartesian(positions.stream().sorted().toList(), groups, 0, "XXXX".toCharArray(), result);
        return result;
    }

    private Map<Integer, Set<Character>> extractPositionGroups(String value) {
        String positionExpression = value.replaceFirst("^(?:全倒|倒)?[一二三四]定", "");
        Map<Integer, Set<Character>> result = new HashMap<>();
        Matcher shared = Pattern.compile("^([0-9]+)((?:头|千|百|十|尾|个){2,4})").matcher(positionExpression);
        if (shared.find()) {
            for (char label : shared.group(2).toCharArray()) addDigits(result, POSITION_INDEX.get(label), shared.group(1));
            return result;
        }
        Matcher sharedSuffix = Pattern.compile("^((?:头|千|百|十|尾|个){2,4})([0-9]+)").matcher(positionExpression);
        if (sharedSuffix.find()) {
            for (char label : sharedSuffix.group(1).toCharArray()) {
                addDigits(result, POSITION_INDEX.get(label), sharedSuffix.group(2));
            }
            return result;
        }
        boolean positionFirst = !positionExpression.isEmpty()
                && POSITION_LABELS.indexOf(positionExpression.charAt(0)) >= 0;
        Matcher matcher = Pattern.compile(positionFirst ? "([头千百十尾个])([0-9]+)" : "([0-9]+)([头千百十尾个])")
                .matcher(positionExpression);
        while (matcher.find()) {
            char label = matcher.group(positionFirst ? 1 : 2).charAt(0);
            String digits = matcher.group(positionFirst ? 2 : 1);
            addDigits(result, POSITION_INDEX.get(label), digits);
        }
        return result;
    }

    private Map<Integer, Set<Character>> extractFivePositionGroups(String value) {
        String positionExpression = value.replace("五位二定", "");
        Map<Integer, Set<Character>> result = new HashMap<>();
        Matcher shared = Pattern.compile("^([0-9]+)((?:千|百|十|个|五){2,5})").matcher(positionExpression);
        if (shared.find()) {
            for (char label : shared.group(2).toCharArray()) {
                addDigits(result, POSITION_INDEX.get(label), shared.group(1));
            }
            return result;
        }
        Matcher sharedSuffix = Pattern.compile("^((?:千|百|十|个|五){2,5})([0-9]+)").matcher(positionExpression);
        if (sharedSuffix.find()) {
            for (char label : sharedSuffix.group(1).toCharArray()) {
                addDigits(result, POSITION_INDEX.get(label), sharedSuffix.group(2));
            }
            return result;
        }
        boolean positionFirst = !positionExpression.isEmpty()
                && "千百十个五".indexOf(positionExpression.charAt(0)) >= 0;
        Matcher matcher = Pattern.compile(positionFirst ? "([千百十个五])([0-9]+)" : "([0-9]+)([千百十个五])")
                .matcher(positionExpression);
        while (matcher.find()) {
            char label = matcher.group(positionFirst ? 1 : 2).charAt(0);
            String digits = matcher.group(positionFirst ? 2 : 1);
            addDigits(result, POSITION_INDEX.get(label), digits);
        }
        return result;
    }

    private List<String> expandFiveTwoPositionExpression(Map<Integer, Set<Character>> groups) {
        Set<Character> fifth = groups.getOrDefault(4, chars(DIGITS));
        List<String> result = new ArrayList<>();
        for (int position = 0; position < 4; position++) {
            Set<Character> first = groups.get(position);
            if (first == null) continue;
            for (Character left : first) {
                for (Character right : fifth) {
                    char[] pattern = "XXXXX".toCharArray();
                    pattern[position] = left;
                    pattern[4] = right;
                    result.add(new String(pattern));
                }
            }
        }
        if (!result.isEmpty()) return result;
        if (!groups.containsKey(4)) throw exception(BET_CONTENT_INVALID);
        for (int position = 0; position < 4; position++) {
            for (Character left : chars(DIGITS)) {
                for (Character right : fifth) {
                    char[] pattern = "XXXXX".toCharArray();
                    pattern[position] = left;
                    pattern[4] = right;
                    result.add(new String(pattern));
                }
            }
        }
        return result;
    }

    private List<String> fullFiveTwoSpace(Set<Character> pool) {
        Map<Integer, Set<Character>> groups = new HashMap<>();
        for (int position = 0; position < 5; position++) groups.put(position, pool);
        return expandFiveTwoPositionExpression(groups);
    }

    private List<String> reverseFixedSpace(String digits, int count, int patternLength, boolean fifthPair) {
        List<String> arrangements = new ArrayList<>();
        multisetArrangements(digits, count, new StringBuilder(), new boolean[digits.length()], arrangements);
        List<List<Integer>> positionSets;
        if (fifthPair) {
            positionSets = List.of(List.of(0, 4), List.of(1, 4), List.of(2, 4), List.of(3, 4));
        } else {
            List<Integer> available = new ArrayList<>();
            for (int position = 0; position < patternLength; position++) available.add(position);
            positionSets = combinations(available, count);
        }
        Set<String> result = new LinkedHashSet<>();
        for (List<Integer> positions : positionSets) {
            for (String arrangement : arrangements) {
                char[] pattern = "X".repeat(patternLength).toCharArray();
                for (int index = 0; index < positions.size(); index++) {
                    pattern[positions.get(index)] = arrangement.charAt(index);
                }
                result.add(new String(pattern));
            }
        }
        return new ArrayList<>(result);
    }

    private List<String> currentBaseSelections(String expression, Matcher currentMatcher, int count) {
        List<String> full = new ArrayList<>();
        combinationsWithReplacement(new ArrayList<>(), new ArrayList<>(chars(DIGITS)), 0, count, full);

        String before = expression.substring(0, currentMatcher.start());
        String after = expression.substring(currentMatcher.end());
        Matcher complex = Pattern.compile("^复式([0-9]+)").matcher(after);
        if (complex.find()) {
            List<String> result = new ArrayList<>();
            combinationsWithReplacement(new ArrayList<>(), new ArrayList<>(chars(complex.group(1))), 0, count, result);
            return result;
        }
        Matcher trailingPool = Pattern.compile("^([0-9]+)$").matcher(after);
        if (trailingPool.find()) {
            List<String> result = new ArrayList<>();
            combinationsWithReplacement(new ArrayList<>(), new ArrayList<>(chars(trailingPool.group(1))), 0, count, result);
            return result;
        }

        Matcher paired = Pattern.compile("^(取|除)?([0-9]+(?:配[0-9]+)+)$").matcher(before);
        if (!paired.matches()) return full;
        List<String> pools = Arrays.asList(paired.group(2).split("配"));
        if (pools.size() != count) throw exception(BET_CONTENT_INVALID);
        Set<String> matched = new LinkedHashSet<>();
        expandCurrentPools(pools, 0, new StringBuilder(), matched);
        if (!"除".equals(paired.group(1))) return new ArrayList<>(matched);
        full.removeIf(matched::contains);
        return full;
    }

    private void expandCurrentPools(List<String> pools, int index, StringBuilder current, Set<String> result) {
        if (index == pools.size()) {
            char[] value = current.toString().toCharArray();
            Arrays.sort(value);
            result.add(new String(value));
            return;
        }
        for (Character digit : chars(pools.get(index))) {
            current.append(digit);
            expandCurrentPools(pools, index + 1, current, result);
            current.deleteCharAt(current.length() - 1);
        }
    }

    private List<String> applyFilters(List<String> input, String expression) {
        List<String> result = new ArrayList<>(input);
        Matcher upper = Pattern.compile("上奖([0-9]+)").matcher(expression);
        if (upper.find()) {
            Set<Character> allowed = chars(upper.group(1));
            Set<Integer> constrained = extractPositionGroups(expression.substring(0, upper.start())).keySet();
            result.removeIf(value -> {
                for (int index = 0; index < value.length(); index++) {
                    char digit = value.charAt(index);
                    if (digit != 'X' && !constrained.contains(index) && !allowed.contains(digit)) return true;
                }
                return false;
            });
        }

        Matcher positionConstraint = Pattern.compile("(取|除)((?:头|千|百|十|尾|个|五)+)([0-9]+)").matcher(expression);
        while (positionConstraint.find()) {
            boolean take = "取".equals(positionConstraint.group(1));
            Set<Integer> positions = positionIndexes(positionConstraint.group(2));
            Set<Character> allowed = chars(positionConstraint.group(3));
            result.removeIf(value -> positions.stream().anyMatch(position -> {
                if (position >= value.length()) return take;
                char digit = value.charAt(position);
                boolean matches = digit != 'X' && allowed.contains(digit);
                return matches != take;
            }));
        }

        Matcher sumMatcher = Pattern.compile("(取|除)?((?:头|千|百|十|尾|个|五){2,5})合([0-9]+)").matcher(expression);
        while (sumMatcher.find()) {
            boolean take = !"除".equals(sumMatcher.group(1));
            Set<Integer> positions = positionIndexes(sumMatcher.group(2));
            Set<Character> allowed = chars(sumMatcher.group(3));
            result.removeIf(value -> matchesPositionSum(value, positions, allowed) != take);
        }
        Matcher twoSum = Pattern.compile("(?:两|二)数合([0-9]+)").matcher(expression);
        while (twoSum.find()) {
            Set<Character> allowed = chars(twoSum.group(1));
            result.removeIf(value -> !matchesCombinationSum(value, 2, allowed));
        }
        Matcher threeSum = Pattern.compile("三数合([0-9]+)").matcher(expression);
        while (threeSum.find()) {
            Set<Character> allowed = chars(threeSum.group(1));
            result.removeIf(value -> !matchesCombinationSum(value, 3, allowed));
        }
        Matcher contains = Pattern.compile("(取|除)?含([0-9]+)").matcher(expression);
        while (contains.find()) {
            boolean take = !"除".equals(contains.group(1));
            Set<Character> allowed = chars(contains.group(2));
            result.removeIf(value -> value.chars().filter(v -> v != 'X')
                    .anyMatch(v -> allowed.contains((char) v)) != take);
        }

        Matcher exclude = Pattern.compile("排除([0-9]+)").matcher(expression);
        while (exclude.find()) {
            Set<Character> denied = chars(exclude.group(1));
            result.removeIf(value -> value.chars().filter(v -> v != 'X')
                    .anyMatch(v -> denied.contains((char) v)));
        }
        Matcher rotated = Pattern.compile("(取|除)全转([0-9]+)").matcher(expression);
        while (rotated.find()) {
            boolean take = "取".equals(rotated.group(1));
            int patternLength = result.stream().findFirst().map(String::length).orElse(4);
            int count = result.stream().findFirst()
                    .map(value -> (int) value.chars().filter(item -> item != 'X').count()).orElse(0);
            Set<String> allowed = new HashSet<>(reverseFixedSpace(rotated.group(2), count, patternLength,
                    patternLength == 5));
            result.removeIf(value -> allowed.contains(value) != take);
        }

        Matcher range = Pattern.compile("(取|除)值(\\d+)值(\\d+)").matcher(expression);
        while (range.find()) {
            boolean take = "取".equals(range.group(1));
            int min = Math.min(Integer.parseInt(range.group(2)), Integer.parseInt(range.group(3)));
            int max = Math.max(Integer.parseInt(range.group(2)), Integer.parseInt(range.group(3)));
            result.removeIf(value -> {
                int total = value.chars().filter(v -> v != 'X').map(v -> v - '0').sum();
                boolean matches = total >= min && total <= max;
                return matches != take;
            });
        }

        Matcher opposite = Pattern.compile("(取|除)对数([0-9]+)").matcher(expression);
        while (opposite.find()) {
            boolean take = "取".equals(opposite.group(1));
            Set<Character> required = chars(opposite.group(2));
            result.removeIf(value -> containsAllDigits(value, required) != take);
        }

        Matcher attributes = Pattern.compile("(取|除)([大小单双X]{2,5})(?![大小单双重])").matcher(expression);
        while (attributes.find()) {
            boolean take = "取".equals(attributes.group(1));
            String pattern = attributes.group(2);
            result.removeIf(value -> matchesAttributePattern(value, pattern) != take);
        }

        Matcher wildcardPositions = Pattern.compile("(取|除)乘号位置((?:头|千|百|十|尾|个|五)+)").matcher(expression);
        while (wildcardPositions.find()) {
            boolean take = "取".equals(wildcardPositions.group(1));
            Set<Integer> positions = positionIndexes(wildcardPositions.group(2));
            result.removeIf(value -> {
                Set<Integer> actual = new LinkedHashSet<>();
                for (int index = 0; index < value.length(); index++) {
                    if (value.charAt(index) == 'X') actual.add(index);
                }
                return actual.equals(positions) != take;
            });
        }

        String action = null;
        Matcher shapeFilters = Pattern.compile("(取|除)|(两双重|双双重|双重|三重|四重|[二三四两]兄弟)")
                .matcher(expression);
        while (shapeFilters.find()) {
            if (shapeFilters.group(1) != null) {
                action = shapeFilters.group(1);
                continue;
            }
            if (action == null) continue;
            boolean take = "取".equals(action);
            String rule = shapeFilters.group(2);
            if (rule.endsWith("兄弟")) {
                int length = rule.charAt(0) == '二' || rule.charAt(0) == '两' ? 2 : rule.charAt(0) == '三' ? 3 : 4;
                result.removeIf(value -> hasSiblingRun(value, length) != take);
            } else {
                String normalizedRule = "两双重".equals(rule) ? "双双重" : rule;
                result.removeIf(value -> matchesRepeat(value, normalizedRule) != take);
            }
        }
        return result;
    }

    private boolean matchesCombinationSum(String value, int count, Set<Character> allowed) {
        // This filter sits on the hot path for high-volume four-position commands. The former generic
        // combination helper created boxed integers, lists and streams for every candidate (up to 10,000 per
        // subcommand). Only two- and three-number sums are part of the command grammar, so evaluate their
        // position combinations directly without per-candidate allocation.
        if (count == 2) {
            for (int first = 0; first < value.length(); first++) {
                char left = value.charAt(first);
                if (left == 'X') continue;
                for (int second = first + 1; second < value.length(); second++) {
                    char right = value.charAt(second);
                    if (right != 'X' && allowed.contains((char) ('0' + (left - '0' + right - '0') % 10))) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (count == 3) {
            for (int first = 0; first < value.length(); first++) {
                char left = value.charAt(first);
                if (left == 'X') continue;
                for (int second = first + 1; second < value.length(); second++) {
                    char middle = value.charAt(second);
                    if (middle == 'X') continue;
                    for (int third = second + 1; third < value.length(); third++) {
                        char right = value.charAt(third);
                        if (right != 'X' && allowed.contains((char) ('0'
                                + (left - '0' + middle - '0' + right - '0') % 10))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        return false;
    }

    private Set<Integer> positionIndexes(String labels) {
        Set<Integer> result = new LinkedHashSet<>();
        for (char label : labels.toCharArray()) {
            Integer position = POSITION_INDEX.get(label);
            if (position != null) result.add(position);
        }
        return result;
    }

    private boolean matchesPositionSum(String value, Set<Integer> positions, Set<Character> allowed) {
        int sum = 0;
        for (Integer position : positions) {
            if (position >= value.length()) return false;
            char digit = value.charAt(position);
            if (digit == 'X') return false;
            sum += digit - '0';
        }
        return allowed.contains((char) ('0' + sum % 10));
    }

    private boolean containsAllDigits(String value, Set<Character> required) {
        Set<Character> actual = value.chars().filter(item -> item != 'X')
                .mapToObj(item -> (char) item).collect(java.util.stream.Collectors.toSet());
        return actual.containsAll(required);
    }

    private boolean matchesAttributePattern(String value, String pattern) {
        if (pattern.length() > value.length()) return false;
        for (int index = 0; index < pattern.length(); index++) {
            if (pattern.charAt(index) == 'X') continue;
            char digit = value.charAt(index);
            if (digit == 'X') return false;
            int number = digit - '0';
            boolean matches = switch (pattern.charAt(index)) {
                case '大' -> number >= 5;
                case '小' -> number < 5;
                case '单' -> number % 2 == 1;
                case '双' -> number % 2 == 0;
                default -> false;
            };
            if (!matches) return false;
        }
        return true;
    }

    private RotatedFixed extractRotatedFixed(String expression) {
        Matcher matcher = Pattern.compile("^(除)?(?:配)?([0-9]+(?:配[0-9]+)+)配?([二三四])(?:字)?定")
                .matcher(expression);
        if (!matcher.find()) return null;
        int count = CHINESE_COUNT.get(matcher.group(3).charAt(0));
        List<Set<Character>> pools = Arrays.stream(matcher.group(2).split("配"))
                .map(this::chars)
                .toList();
        if (pools.size() < 2 || pools.size() > count) throw exception(BET_CONTENT_INVALID);
        return new RotatedFixed(pools, count, matcher.group(1) != null);
    }

    private RotatedFixed extractRotatedFiveTwo(String expression) {
        Matcher matcher = Pattern.compile("^(除)?(?:配)?([0-9]+配[0-9]+)配?五位二定")
                .matcher(expression);
        if (!matcher.find()) return null;
        List<Set<Character>> pools = Arrays.stream(matcher.group(2).split("配"))
                .map(this::chars)
                .toList();
        return new RotatedFixed(pools, 2, matcher.group(1) != null);
    }

    private List<String> applyRotatedFixedFilter(List<String> input, RotatedFixed rotatedFixed) {
        List<String> result = new ArrayList<>(input);
        result.removeIf(value -> matchesRotatedPools(value, rotatedFixed.pools(), 0, new boolean[value.length()])
                == rotatedFixed.exclude());
        return result;
    }

    private boolean matchesRotatedPools(String value, List<Set<Character>> pools, int poolIndex,
                                        boolean[] usedPositions) {
        if (poolIndex == pools.size()) return true;
        Set<Character> pool = pools.get(poolIndex);
        for (int position = 0; position < value.length(); position++) {
            if (usedPositions[position] || value.charAt(position) == 'X' || !pool.contains(value.charAt(position))) {
                continue;
            }
            usedPositions[position] = true;
            if (matchesRotatedPools(value, pools, poolIndex + 1, usedPositions)) return true;
            usedPositions[position] = false;
        }
        return false;
    }

    private Integer explicitFixedCount(String expression) {
        Matcher matcher = Pattern.compile("(?:全倒|倒)?([一二三四])定").matcher(expression);
        return matcher.find() ? CHINESE_COUNT.get(matcher.group(1).charAt(0)) : null;
    }

    private List<String> fullFixedSpace(int count) {
        return fullFixedSpace(count, 4, chars(DIGITS));
    }

    private List<String> fullFixedSpace(int count, int patternLength, Set<Character> digits) {
        List<String> result = new ArrayList<>();
        List<Integer> available = new ArrayList<>();
        for (int position = 0; position < patternLength; position++) available.add(position);
        for (List<Integer> positions : combinations(available, count)) {
            Map<Integer, Set<Character>> groups = new HashMap<>();
            positions.forEach(position -> groups.put(position, digits));
            expandCartesian(positions, groups, 0, "X".repeat(patternLength).toCharArray(), result);
        }
        return result;
    }

    private int detectFixedCount(String expression) {
        Integer explicit = explicitFixedCount(expression);
        if (explicit != null) return explicit;
        int size = extractPositionGroups(expression).size();
        if (size >= 1 && size <= 4) return size;
        Set<Integer> positions = new HashSet<>();
        Matcher sums = Pattern.compile("((?:头|千|百|十|尾|个){2,4})合").matcher(expression);
        while (sums.find()) {
            positions.addAll(positionIndexes(sums.group(1)));
        }
        if (!positions.isEmpty()) return positions.size();
        throw exception(BET_CONTENT_INVALID);
    }

    private ParsedBet basicBet(String selection, BigDecimal amount, List<OddDO> odds) {
        String play = "大小".contains(selection) ? "大小" : "单双".contains(selection) ? "单双" : "龙虎";
        if ("龙虎".equals(play)) {
            String code = "和".equals(selection) ? "regexh" : "regexlh";
            return configuredBet(play, selection, amount, code, odds);
        }
        OddDO configured = odds.stream().filter(item -> "启用".equals(item.getStatus())
                && (selection.equals(item.getItem()) || selection.equals(item.getPlay()))).findFirst().orElse(null);
        return new ParsedBet(play, selection, amount,
                configured == null ? new BigDecimal("1.98") : configured.getRate());
    }

    private ParsedBet configuredBet(String play, String selection, BigDecimal amount, String code, List<OddDO> odds) {
        OddDO odd = configuredOdd(code, amount, odds);
        return new ParsedBet(play, selection, amount, odd.getRate());
    }

    private OddDO configuredOdd(String code, BigDecimal amount, List<OddDO> odds) {
        OddDO odd = odds.stream().filter(item -> code.equals(item.getCode())).findFirst().orElse(null);
        if (odd == null && FIVE_POSITION_TWO_ODD_CODE.equals(code)) {
            // Existing owners created before the dedicated setting was introduced inherit 二定位
            // until the idempotent data repair adds their independent 五位二定 row.
            odd = odds.stream().filter(item -> "regex2d".equals(item.getCode())).findFirst().orElse(null);
        }
        if (odd == null || !"启用".equals(odd.getStatus()) || odd.getRate() == null || odd.getRate().signum() <= 0) {
            throw exception(PLAY_TYPE_DISABLED);
        }
        if (odd.getMinLimit() != null && amount.compareTo(odd.getMinLimit()) < 0
                || odd.getMaxLimit() != null && amount.compareTo(odd.getMaxLimit()) > 0) {
            throw exception(BET_LIMIT_INVALID);
        }
        return odd;
    }

    private BigDecimal amount(String value) {
        try {
            BigDecimal amount = new BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
            if (amount.signum() <= 0) throw new NumberFormatException();
            return amount;
        } catch (NumberFormatException ex) {
            throw exception(BET_CONTENT_INVALID);
        }
    }

    private List<String> splitCommands(String rawContent) {
        String value = rawContent == null ? "" : rawContent.trim();
        if (value.isEmpty()) return List.of(value);
        Matcher amountMatcher = Pattern.compile(
                "各\\d+(?:\\.\\d+)?|(?:倒[一二三四]定|[二三四]定倒)/\\d+(?:\\.\\d+)?")
                .matcher(value);
        List<String> commands = new ArrayList<>();
        int start = 0;
        while (amountMatcher.find()) {
            String command = value.substring(start, amountMatcher.end())
                    .replaceFirst("^[，,、；;\\s]+", "")
                    .trim();
            if (!command.isEmpty()) commands.add(command);
            start = amountMatcher.end();
        }
        String remaining = value.substring(start).replaceAll("[，,、；;\\s]+", "");
        if (!remaining.isEmpty() || commands.size() <= 1) return List.of(value);
        return commands;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("[，,、；;\\s]+", "")
                .replace('：', ':').replace('末', '尾').replace("两定", "二定");
    }

    private String normalizeReferenceAmountAlias(String content) {
        Matcher matcher = Pattern.compile(
                "^(.*(?:倒[一二三四]定|[二三四]定倒))/(\\d+(?:\\.\\d+)?)$").matcher(content);
        if (!matcher.matches()) return content;
        return matcher.group(1) + "各" + matcher.group(2);
    }

    private String normalizeReverseFixedAlias(String content) {
        Matcher matcher = Pattern.compile("^([0-9]+)([二三四])定倒(.*各\\d+(?:\\.\\d+)?)$").matcher(content);
        if (matcher.matches()) {
            return matcher.group(1) + "倒" + matcher.group(2) + "定" + matcher.group(3);
        }
        Matcher implicitFour = Pattern.compile("^([0-9]+)倒(?![一二三四]定)(.*各\\d+(?:\\.\\d+)?)$").matcher(content);
        if (implicitFour.matches()) {
            return implicitFour.group(1) + "倒四定" + implicitFour.group(2);
        }
        return content;
    }

    private String expandNumericFixedShorthand(String content) {
        Matcher matcher = Pattern.compile("^(\\d{1,4})各(\\d+(?:\\.\\d+)?)$").matcher(content);
        if (!matcher.matches()) return content;
        String digits = matcher.group(1);
        String positions = switch (digits.length()) {
            case 1 -> "个";
            case 2 -> "十个";
            case 3 -> "百十个";
            default -> "千百十个";
        };
        StringBuilder expanded = new StringBuilder();
        for (int index = 0; index < digits.length(); index++) {
            expanded.append(positions.charAt(index)).append(digits.charAt(index));
        }
        return expanded.append("各").append(matcher.group(2)).toString();
    }

    private List<IndexedBet> indexed(List<String> selections, String play, BigDecimal amount, BigDecimal odds, int index) {
        return selections.stream().map(selection -> new IndexedBet(index,
                new ParsedBet(play, selection, amount, odds))).toList();
    }

    private void addPositionProduct(Set<String> values, int left, int right, String leftDigits, String rightDigits) {
        for (char a : leftDigits.toCharArray()) for (char b : rightDigits.toCharArray()) {
            char[] pattern = "XXXX".toCharArray();
            pattern[left] = a;
            pattern[right] = b;
            values.add(new String(pattern));
        }
    }

    private void addDigits(Map<Integer, Set<Character>> result, int position, String values) {
        result.computeIfAbsent(position, ignored -> new LinkedHashSet<>()).addAll(chars(values));
    }

    private Set<Character> chars(String value) {
        Set<Character> result = new LinkedHashSet<>();
        for (char item : value.toCharArray()) result.add(item);
        return result;
    }

    private void combinationsWithReplacement(List<Character> current, int start, int count, List<String> result) {
        combinationsWithReplacement(current, new ArrayList<>(chars(DIGITS)), start, count, result);
    }

    private void combinationsWithReplacement(List<Character> current, List<Character> source,
                                             int start, int count, List<String> result) {
        if (current.size() == count) {
            result.add(current.stream().map(String::valueOf).reduce("", String::concat));
            return;
        }
        for (int index = start; index < source.size(); index++) {
            current.add(source.get(index));
            combinationsWithReplacement(current, source, index, count, result);
            current.remove(current.size() - 1);
        }
    }

    private void multisetArrangements(String digits, int count, StringBuilder current, boolean[] used, List<String> result) {
        if (current.length() == count) {
            result.add(current.toString());
            return;
        }
        Set<Character> level = new HashSet<>();
        for (int index = 0; index < digits.length(); index++) {
            if (used[index] || !level.add(digits.charAt(index))) continue;
            used[index] = true;
            current.append(digits.charAt(index));
            multisetArrangements(digits, count, current, used, result);
            current.deleteCharAt(current.length() - 1);
            used[index] = false;
        }
    }

    private List<List<Integer>> choosePositions(int count) {
        return combinations(List.of(0, 1, 2, 3), count);
    }

    private <T> List<List<T>> combinations(List<T> source, int count) {
        List<List<T>> result = new ArrayList<>();
        combine(source, count, 0, new ArrayList<>(), result);
        return result;
    }

    private <T> void combine(List<T> source, int count, int start, List<T> current, List<List<T>> result) {
        if (current.size() == count) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (int index = start; index < source.size(); index++) {
            current.add(source.get(index));
            combine(source, count, index + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    private void expandCartesian(List<Integer> positions, Map<Integer, Set<Character>> values, int index,
                                 char[] pattern, List<String> result) {
        if (index == positions.size()) {
            result.add(new String(pattern));
            return;
        }
        int position = positions.get(index);
        for (Character value : values.get(position)) {
            pattern[position] = value;
            expandCartesian(positions, values, index + 1, pattern, result);
        }
        pattern[position] = 'X';
    }

    private boolean matchesRepeat(String value, String rule) {
        Map<Character, Integer> counts = new HashMap<>();
        value.chars().filter(item -> item != 'X').forEach(item -> counts.merge((char) item, 1, Integer::sum));
        if ("双双重".equals(rule)) return counts.values().stream().mapToInt(count -> count / 2).sum() >= 2;
        int expected = "双重".equals(rule) ? 2 : "三重".equals(rule) ? 3 : 4;
        return counts.values().stream().anyMatch(count -> count >= expected);
    }

    private boolean hasSiblingRun(String value, int expected) {
        Set<Integer> values = new HashSet<>();
        value.chars().filter(item -> item != 'X').forEach(item -> values.add(item - '0'));
        for (int start = 0; start < 10; start++) {
            boolean matches = true;
            for (int offset = 0; offset < expected; offset++) {
                if (!values.contains((start + offset) % 10)) {
                    matches = false;
                    break;
                }
            }
            if (matches) return true;
        }
        return false;
    }

    private String chineseCount(int value) {
        return switch (value) {
            case 1 -> "一";
            case 2 -> "二";
            case 3 -> "三";
            case 4 -> "四";
            default -> String.valueOf(value);
        };
    }

    private record IndexedBet(int index, ParsedBet bet) {
    }

    private record RotatedFixed(List<Set<Character>> pools, int count, boolean exclude) {
    }
}
