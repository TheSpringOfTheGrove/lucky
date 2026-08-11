package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.OddDO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private static final String POSITION_LABELS = "头千百十尾个";
    private static final Map<Character, Integer> POSITION_INDEX = Map.of(
            '头', 0, '千', 0, '百', 1, '十', 2, '尾', 3, '个', 3);
    private static final Map<Character, Integer> CHINESE_COUNT = Map.of('一', 1, '二', 2, '三', 3, '四', 4);

    public record ParsedBet(String play, String selection, BigDecimal amount, BigDecimal odds) {
    }

    public record DrawResult(String result, List<Integer> digits, String bigSmall, String oddEven,
                             String dragonTiger) {
    }

    public List<ParsedBet> parse(String rawContent, List<OddDO> odds) {
        String content = normalize(rawContent);
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
        if (result.size() > 10000) {
            throw exception(BET_LIMIT_INVALID);
        }
        return result;
    }

    public DrawResult deriveDraw(String rawResult) {
        List<Integer> digits = rawResult.chars().filter(Character::isDigit).map(value -> value - '0').boxed().toList();
        if (digits.size() < 3 || digits.size() > 5) {
            throw exception(BET_CONTENT_INVALID);
        }
        int sum = digits.stream().mapToInt(Integer::intValue).sum();
        int threshold = (int) Math.ceil(digits.size() * 4.5);
        int first = digits.get(0);
        int last = digits.get(digits.size() - 1);
        return new DrawResult(digits.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""),
                digits, sum >= threshold ? "大" : "小", sum % 2 == 0 ? "双" : "单",
                first == last ? "和" : first > last ? "龙" : "虎");
    }

    public boolean isWinning(ParsedBet bet, DrawResult draw) {
        if ("大小".equals(bet.play())) return bet.selection().equals(draw.bigSmall());
        if ("单双".equals(bet.play())) return bet.selection().equals(draw.oddEven());
        if ("龙虎".equals(bet.play())) return bet.selection().equals(draw.dragonTiger());
        String digits = draw.digits().stream().map(String::valueOf).reduce("", String::concat);
        if (bet.play().endsWith("定位") || "五位二定".equals(bet.play())) {
            if (bet.selection().contains("X")) {
                String positionedDigits = digits.length() == 5 && bet.selection().length() == 4
                        ? digits.substring(1) : digits;
                if (positionedDigits.length() != bet.selection().length()) return false;
                for (int i = 0; i < positionedDigits.length(); i++) {
                    char expected = bet.selection().charAt(i);
                    if (expected != 'X' && expected != positionedDigits.charAt(i)) return false;
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
            OddDO odd = configuredOdd("regex2d", amount, odds);
            int modeAt = expression.indexOf("五位二定");
            String before = expression.substring(0, modeAt);
            String after = expression.substring(modeAt + "五位二定".length());
            Matcher thousandMatcher = Pattern.compile("千([0-9]+)").matcher(before + after);
            if (!thousandMatcher.find()) throw exception(BET_CONTENT_INVALID);
            String thousand = thousandMatcher.group(1);
            Matcher fifthMatcher = Pattern.compile("五([0-9]+)").matcher(modeAt > 0 ? before : after);
            String fifth = fifthMatcher.find() ? fifthMatcher.group(1) : DIGITS;
            List<String> selections = new ArrayList<>();
            for (char first : thousand.toCharArray()) {
                for (char last : fifth.toCharArray()) selections.add(first + "XXX" + last);
            }
            return indexed(selections, "五位二定", amount, odd.getRate(), content.length());
        }

        Matcher currentMatcher = Pattern.compile("([二三四])现").matcher(expression);
        if (currentMatcher.find()) {
            int count = CHINESE_COUNT.get(currentMatcher.group(1).charAt(0));
            String code = "regex" + count + "x";
            OddDO odd = configuredOdd(code, amount, odds);
            List<String> selections = new ArrayList<>();
            combinationsWithReplacement(new ArrayList<>(), 0, count, selections);
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

        Matcher pairMatcher = Pattern.compile("^([0-9]+)配([0-9]+)配二定").matcher(expression);
        if (pairMatcher.find()) {
            OddDO odd = configuredOdd("regex2d", amount, odds);
            Set<String> values = new LinkedHashSet<>();
            for (int left = 0; left < 4; left++) {
                for (int right = left + 1; right < 4; right++) {
                    addPositionProduct(values, left, right, pairMatcher.group(1), pairMatcher.group(2));
                    addPositionProduct(values, left, right, pairMatcher.group(2), pairMatcher.group(1));
                }
            }
            return indexed(new ArrayList<>(values), "二定位", amount, odd.getRate(), content.length());
        }

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

        int count = detectFixedCount(expression);
        OddDO odd = configuredOdd("regex" + count + "d", amount, odds);
        List<String> selections = expandPositionExpression(expression, count);
        selections = applyFilters(selections, expression);
        if (selections.isEmpty()) throw exception(BET_CONTENT_INVALID);
        return indexed(new ArrayList<>(new java.util.TreeSet<>(selections)), chineseCount(count) + "定位",
                amount, odd.getRate(), content.length());
    }

    private List<String> expandPositionExpression(String expression, int count) {
        if (Pattern.compile("^[一二三四]定").matcher(expression).find()
                && Pattern.compile("[头千百十尾个]{2}合").matcher(expression).find()) {
            return completeSumSpace(expression);
        }
        String base = expression.split("。")[0].split("(?:除|含|取值)")[0];
        Map<Integer, Set<Character>> groups = extractPositionGroups(base);
        if (groups.isEmpty() && expression.contains("合")) {
            return completeSumSpace(expression);
        }
        if (groups.isEmpty()) throw exception(BET_CONTENT_INVALID);
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
        Matcher sum = Pattern.compile("([头千百十尾个])([头千百十尾个])合").matcher(expression);
        while (sum.find()) {
            positions.add(POSITION_INDEX.get(sum.group(1).charAt(0)));
            positions.add(POSITION_INDEX.get(sum.group(2).charAt(0)));
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
        Map<Integer, Set<Character>> result = new HashMap<>();
        Matcher shared = Pattern.compile("^([0-9]+)((?:头|千|百|十|尾|个){2,4})").matcher(value);
        if (shared.find()) {
            for (char label : shared.group(2).toCharArray()) addDigits(result, POSITION_INDEX.get(label), shared.group(1));
            return result;
        }
        boolean positionFirst = !value.isEmpty() && POSITION_LABELS.indexOf(value.charAt(0)) >= 0;
        Matcher matcher = Pattern.compile(positionFirst ? "([头千百十尾个])([0-9]+)" : "([0-9]+)([头千百十尾个])").matcher(value);
        while (matcher.find()) {
            char label = matcher.group(positionFirst ? 1 : 2).charAt(0);
            String digits = matcher.group(positionFirst ? 2 : 1);
            addDigits(result, POSITION_INDEX.get(label), digits);
        }
        return result;
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
        Matcher sumMatcher = Pattern.compile("([头千百十尾个])([头千百十尾个])合([0-9]+)").matcher(expression);
        while (sumMatcher.find()) {
            int left = POSITION_INDEX.get(sumMatcher.group(1).charAt(0));
            int right = POSITION_INDEX.get(sumMatcher.group(2).charAt(0));
            Set<Character> allowed = chars(sumMatcher.group(3));
            result.removeIf(value -> value.charAt(left) == 'X' || value.charAt(right) == 'X'
                    || !allowed.contains((char) ('0' + ((value.charAt(left) - '0' + value.charAt(right) - '0') % 10))));
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
        Matcher contains = Pattern.compile("含([0-9]+)").matcher(expression);
        while (contains.find()) {
            Set<Character> allowed = chars(contains.group(1));
            result.removeIf(value -> value.chars().filter(v -> v != 'X').noneMatch(v -> allowed.contains((char) v)));
        }
        Matcher range = Pattern.compile("取值(\\d+)值(\\d+)").matcher(expression);
        while (range.find()) {
            int min = Math.min(Integer.parseInt(range.group(1)), Integer.parseInt(range.group(2)));
            int max = Math.max(Integer.parseInt(range.group(1)), Integer.parseInt(range.group(2)));
            result.removeIf(value -> {
                int total = value.chars().filter(v -> v != 'X').map(v -> v - '0').sum();
                return total < min || total > max;
            });
        }
        Matcher repeat = Pattern.compile("(取|除)(双双重|双重|三重|四重)").matcher(expression);
        while (repeat.find()) {
            boolean take = "取".equals(repeat.group(1));
            String rule = repeat.group(2);
            result.removeIf(value -> matchesRepeat(value, rule) != take);
        }
        Matcher siblings = Pattern.compile("(取|除)([二三四两])兄弟").matcher(expression);
        while (siblings.find()) {
            boolean take = "取".equals(siblings.group(1));
            int length = siblings.group(2).matches("[二两]") ? 2 : "三".equals(siblings.group(2)) ? 3 : 4;
            result.removeIf(value -> hasSiblingRun(value, length) != take);
        }
        return result;
    }

    private boolean matchesCombinationSum(String value, int count, Set<Character> allowed) {
        List<Integer> digits = value.chars().filter(item -> item != 'X').map(item -> item - '0').boxed().toList();
        return combinations(digits, count).stream().anyMatch(part -> {
            int sum = part.stream().mapToInt(Integer::intValue).sum() % 10;
            return allowed.contains((char) ('0' + sum));
        });
    }

    private int detectFixedCount(String expression) {
        Matcher explicit = Pattern.compile("(?:倒|全倒)?([一二三四])定").matcher(expression);
        if (explicit.find()) return CHINESE_COUNT.get(explicit.group(1).charAt(0));
        int size = extractPositionGroups(expression).size();
        if (size >= 1 && size <= 4) return size;
        Set<Integer> positions = new HashSet<>();
        Matcher sums = Pattern.compile("([头千百十尾个])([头千百十尾个])合").matcher(expression);
        while (sums.find()) {
            positions.add(POSITION_INDEX.get(sums.group(1).charAt(0)));
            positions.add(POSITION_INDEX.get(sums.group(2).charAt(0)));
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("[，,、；;\\s]+", "").replace('：', ':').replace('末', '尾');
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
        if (current.size() == count) {
            result.add(current.stream().map(String::valueOf).reduce("", String::concat));
            return;
        }
        for (int index = start; index < DIGITS.length(); index++) {
            current.add(DIGITS.charAt(index));
            combinationsWithReplacement(current, index, count, result);
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
}
