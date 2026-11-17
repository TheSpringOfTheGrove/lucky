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
        if (image == null || image.contains("data-draw-format=\"10\"")
                || !image.startsWith("<svg ") || !image.contains("width=\"381\" height=\"661\"")) {
            return image;
        }
        boolean card = image.contains("data-draw-format=\"3\"") || image.contains("data-draw-format=\"4\"")
                || image.contains("data-draw-format=\"5\"") || image.contains("data-draw-format=\"6\"")
                || image.contains("data-draw-format=\"7\"")
                || image.contains("data-draw-format=\"8\"")
                || image.contains("data-draw-format=\"9\"");
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
        StringBuilder svg = new StringBuilder(10000);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" data-draw-format=\"10\" width=\"381\" height=\"661\" viewBox=\"0 0 381 661\">")
                .append("<defs>")
                .append("<linearGradient id=\"panel\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">")
                .append("<stop offset=\"0\" stop-color=\"#edf8ff\"/><stop offset=\"0.52\" stop-color=\"#cfeaff\"/><stop offset=\"1\" stop-color=\"#a9d9fb\"/>")
                .append("</linearGradient>")
                .append("<linearGradient id=\"header\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">")
                .append("<stop offset=\"0\" stop-color=\"#0879df\"/><stop offset=\"0.55\" stop-color=\"#045fc4\"/><stop offset=\"1\" stop-color=\"#084caa\"/>")
                .append("</linearGradient>")
                .append("<linearGradient id=\"tile\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">")
                .append("<stop offset=\"0\" stop-color=\"#f3f9ff\"/><stop offset=\"1\" stop-color=\"#deeffc\"/>")
                .append("</linearGradient>")
                .append("<filter id=\"rowShadow\" x=\"-10%\" y=\"-20%\" width=\"120%\" height=\"150%\">")
                .append("<feDropShadow dx=\"0\" dy=\"1.2\" stdDeviation=\"1.3\" flood-color=\"#3b91d5\" flood-opacity=\"0.26\"/>")
                .append("</filter></defs>")
                .append("<rect x=\"1\" y=\"1\" width=\"379\" height=\"659\" rx=\"18\" fill=\"url(#panel)\" stroke=\"#79bbed\" stroke-width=\"2\"/>")
                .append("<rect x=\"5\" y=\"5\" width=\"371\" height=\"651\" rx=\"15\" fill=\"none\" stroke=\"#fff\" stroke-opacity=\"0.7\"/>")
                .append("<rect x=\"12\" y=\"9\" width=\"357\" height=\"36\" rx=\"15\" fill=\"url(#header)\"/>")
                .append("<path d=\"M238 9h131v17c-30 8-64 2-94-3-16-3-27-2-37 1z\" fill=\"#4b9deb\" opacity=\"0.3\"/>");
        centerLabel(svg, 51, 33, "次数", "#ffffff", 16);
        centerLabel(svg, 114, 33, "时间", "#ffffff", 16);
        centerLabel(svg, 248, 33, "VIP入住房号", "#ffffff", 15);
        for (int index = 0; index < 15; index++) {
            int rowTop = 51 + index * 40;
            svg.append("<rect x=\"12\" y=\"").append(rowTop)
                    .append("\" width=\"357\" height=\"35\" rx=\"10\" fill=\"#fff\" filter=\"url(#rowShadow)\"/>");
            if (history == null || index >= history.size()) continue;
            Row row = history.get(index);
            int baseline = rowTop + 23;
            String period = row.period() == null ? "" : row.period();
            label(svg, 25, baseline, period.substring(Math.max(0, period.length() - 3)), "#153b60", 17, true);
            label(svg, 88, baseline, row.time(), "#153b60", 16, true);
            String result = row.result() == null ? "" : row.result().replaceAll("\\D", "");
            if (result.length() != 5) continue;
            for (int digit = 0; digit < 5; digit++) {
                int x = 185 + digit * 26;
                svg.append("<rect x=\"").append(x).append("\" y=\"").append(rowTop + 6)
                        .append("\" width=\"22\" height=\"24\" rx=\"5\" fill=\"url(#tile)\" stroke=\"#ffffff\" stroke-opacity=\"0.8\"/>");
                centerLabel(svg, x + 11, baseline, String.valueOf(result.charAt(digit)),
                        digitColor(result.charAt(digit)), 17);
            }
            svg.append("<path d=\"M333 ").append(rowTop + 18).append("l5 5 11-14\" fill=\"none\" stroke=\"#e00018\"")
                    .append(" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>");
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
            default -> "#214a70";
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
