package com.hnz.luck5.module.lottery.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A fixed-size, replayable draw image for a room message. */
@Component
public class LotteryDrawHistoryImageRenderer {

    private static final Pattern LEGACY_TEXT = Pattern.compile(
            "<text x=\"(14|71|158|195|232|269|306)\" y=\"(\\d+)\"[^>]*>([^<]*)</text>");
    private static final Pattern CARD_TEXT = Pattern.compile(
            "<text x=\"(25|88|196|222|248|274|300)\" y=\"(\\d+)\"[^>]*>([^<]*)</text>");

    public record Row(String period, String time, String result) {
    }

    public String render(List<Row> history) {
        return renderCard(history);
    }

    /** Replay saved old snapshots in the selected card style without changing their numbers or times. */
    public static String completeSnapshot(String image) {
        if (image == null || image.contains("data-draw-format=\"6\"")
                || !image.startsWith("<svg ") || !image.contains("width=\"381\" height=\"661\"")) {
            return image;
        }
        boolean card = image.contains("data-draw-format=\"3\"") || image.contains("data-draw-format=\"4\"")
                || image.contains("data-draw-format=\"5\"");
        Map<Integer, LegacyRow> legacyRows = new TreeMap<>();
        Matcher matcher = (card ? CARD_TEXT : LEGACY_TEXT).matcher(image);
        while (matcher.find()) {
            int baseline = Integer.parseInt(matcher.group(2));
            int firstBaseline = card ? 74 : 72;
            int rowHeight = card ? 40 : 41;
            if (baseline < firstBaseline || (baseline - firstBaseline) % rowHeight != 0
                    || (baseline - firstBaseline) / rowHeight >= 15) {
                continue;
            }
            int x = Integer.parseInt(matcher.group(1));
            String value = matcher.group(3);
            LegacyRow row = legacyRows.computeIfAbsent(baseline, ignored -> new LegacyRow());
            if (x == (card ? 25 : 14)) {
                row.period = value;
            } else if (x == (card ? 88 : 71)) {
                row.time = value;
            } else if (value.length() == 1 && Character.isDigit(value.charAt(0))) {
                row.result[card ? (x - 196) / 26 : (x - 158) / 37] = value.charAt(0);
            }
        }
        if (legacyRows.isEmpty()) return image;
        List<Row> rows = new ArrayList<>(legacyRows.size());
        for (LegacyRow legacy : legacyRows.values()) {
            StringBuilder digits = new StringBuilder(5);
            for (char digit : legacy.result) {
                if (digit != 0) digits.append(digit);
            }
            rows.add(new Row(legacy.period, legacy.time, digits.toString()));
        }
        return renderCard(rows);
    }

    private static String renderCard(List<Row> history) {
        StringBuilder svg = new StringBuilder(8500);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" data-draw-format=\"6\" width=\"381\" height=\"661\" viewBox=\"0 0 381 661\">")
                .append("<rect width=\"381\" height=\"661\" rx=\"16\" fill=\"#e8f3f2\"/>");
        label(svg, 25, 32, "次数", "#31575b", 16, true);
        label(svg, 88, 32, "时间", "#31575b", 16, true);
        label(svg, 185, 32, "VIP 入 住 房 号", "#31575b", 16, true);
        for (int index = 0; index < 15; index++) {
            int rowTop = 51 + index * 40;
            svg.append("<rect x=\"12\" y=\"").append(rowTop)
                    .append("\" width=\"357\" height=\"35\" rx=\"9\" fill=\"#fff\"/>");
            if (history == null || index >= history.size()) continue;
            Row row = history.get(index);
            int baseline = rowTop + 23;
            String period = row.period() == null ? "" : row.period();
            label(svg, 25, baseline, period.substring(Math.max(0, period.length() - 3)), "#177d81", 17, true);
            label(svg, 88, baseline, row.time(), "#193b40", 16, true);
            String result = row.result() == null ? "" : row.result().replaceAll("\\D", "");
            if (result.length() != 5) continue;
            for (int digit = 0; digit < 5; digit++) {
                int x = 185 + digit * 26;
                svg.append("<rect x=\"").append(x).append("\" y=\"").append(rowTop + 6)
                        .append("\" width=\"22\" height=\"24\" rx=\"5\" fill=\"#edf5f5\"/>");
                centerLabel(svg, x + 11, baseline, String.valueOf(result.charAt(digit)),
                        digitColor(result.charAt(digit)), 17);
            }
            // Use a plain glyph so SVG fill controls the requested red color across browsers.
            label(svg, 329, baseline, "√", "#e5484d", 20, true);
        }
        return svg.append("</svg>").toString();
    }

    private static void label(StringBuilder svg, int x, int y, String text, String color, int size, boolean bold) {
        svg.append("<text x=\"").append(x).append("\" y=\"").append(y)
                .append("\" fill=\"").append(color).append("\" font-size=\"").append(size)
                .append("\" font-weight=\"").append(bold ? "700" : "400")
                .append("\" font-family=\"Arial,Microsoft YaHei,sans-serif\">")
                .append(escape(text)).append("</text>");
    }

    private static void centerLabel(StringBuilder svg, int x, int y, String text, String color, int size) {
        svg.append("<text x=\"").append(x).append("\" y=\"").append(y)
                .append("\" text-anchor=\"middle\" fill=\"").append(color)
                .append("\" font-size=\"").append(size)
                .append("\" font-weight=\"700\" font-family=\"Arial,Microsoft YaHei,sans-serif\">")
                .append(escape(text)).append("</text>");
    }

    private static String digitColor(char digit) {
        return switch (digit) {
            case '1' -> "#8d3299";
            case '6' -> "#ff4f82";
            case '8' -> "#409eff";
            case '9' -> "#10c957";
            default -> "#193b40";
        };
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final class LegacyRow {
        private String period = "";
        private String time = "";
        private final char[] result = new char[5];
    }
}
