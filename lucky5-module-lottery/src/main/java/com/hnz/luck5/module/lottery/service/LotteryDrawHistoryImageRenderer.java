package com.hnz.luck5.module.lottery.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A fixed-size, replayable draw image for a room message. */
@Component
public class LotteryDrawHistoryImageRenderer {

    public record Row(String period, String time, String result) {
    }

    public String render(List<Row> history) {
        StringBuilder svg = new StringBuilder(6500);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"381\" height=\"661\" viewBox=\"0 0 381 661\">")
                .append("<rect width=\"381\" height=\"661\" fill=\"#fff\"/>")
                .append("<rect x=\"3\" y=\"5\" width=\"375\" height=\"36\" fill=\"#2164dd\"/>");
        label(svg, 11, 31, "期数", "#fff", 23);
        label(svg, 77, 31, "时间", "#fff", 23);
        label(svg, 232, 31, "成功", "#fff", 23);
        for (int index = 0; index < 15; index++) {
            int rowTop = 43 + index * 41;
            if (index % 2 == 0) {
                svg.append("<rect x=\"3\" y=\"").append(rowTop)
                        .append("\" width=\"375\" height=\"40\" fill=\"#ededed\"/>");
            }
            if (history == null || index >= history.size()) continue;
            Row row = history.get(index);
            int baseline = rowTop + 29;
            String period = row.period() == null ? "" : row.period();
            label(svg, 14, baseline, period.substring(Math.max(0, period.length() - 3)), "#555", 22);
            label(svg, 71, baseline, row.time(), "#555", 22);
            String result = row.result() == null ? "" : row.result().replaceAll("\\D", "");
            if (result.length() != 5) continue;
            for (int digit = 0; digit < 5; digit++) {
                char number = result.charAt(digit);
                label(svg, 158 + digit * 37, baseline, String.valueOf(number), digitColor(number), 22);
            }
        }
        return completeSnapshot(svg.append("</svg>").toString());
    }

    /** Add the result column to our old snapshots without replacing their saved numbers or times. */
    public static String completeSnapshot(String image) {
        if (image == null || image.contains("data-draw-format=\"2\"")
                || !image.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"381\" height=\"661\"")) {
            return image;
        }
        Matcher digits = Pattern.compile("<text x=\"(158|195|232|269|306)\" y=\"(\\d+)\"[^>]*>([0-9])</text>")
                .matcher(image);
        Map<Integer, char[]> rows = new TreeMap<>();
        while (digits.find()) {
            char[] numbers = rows.computeIfAbsent(Integer.parseInt(digits.group(2)), ignored -> new char[5]);
            numbers[(Integer.parseInt(digits.group(1)) - 158) / 37] = digits.group(3).charAt(0);
        }
        StringBuilder results = new StringBuilder();
        rows.forEach((baseline, numbers) -> {
            if (numbers[0] == 0 || numbers[3] == 0) return;
            // Only the first and fourth balls determine the result; ball five is display-only.
            String result = numbers[0] > numbers[3] ? "龙" : numbers[0] < numbers[3] ? "虎" : "和";
            String color = "龙".equals(result) ? "#ff1493" : "虎".equals(result) ? "#0000ff" : "#00dd44";
            label(results, 343, baseline, result, color, 23);
        });
        return image.replace("<svg ", "<svg data-draw-format=\"2\" ")
                .replace("</svg>", results + "</svg>");
    }

    private static void label(StringBuilder svg, int x, int y, String text, String color, int size) {
        svg.append("<text x=\"").append(x).append("\" y=\"").append(y)
                .append("\" fill=\"").append(color).append("\" font-size=\"").append(size)
                .append("\" font-family=\"Times New Roman,SimSun,serif\">")
                .append(escape(text)).append("</text>");
    }

    private static String digitColor(char digit) {
        return switch (digit) {
            case '1' -> "#8d3299";
            case '6' -> "#ff4f82";
            case '8' -> "#409eff";
            case '9' -> "#10c957";
            default -> "#777";
        };
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
