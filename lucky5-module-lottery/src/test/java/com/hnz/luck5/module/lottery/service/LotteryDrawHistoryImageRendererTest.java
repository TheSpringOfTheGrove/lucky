package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryDrawHistoryImageRendererTest {

    @Test
    void shouldKeepReferenceImageSizeAndRows() {
        String image = new LotteryDrawHistoryImageRenderer().render(List.of(
                new LotteryDrawHistoryImageRenderer.Row("20260927268", "22:20", "86165"),
                new LotteryDrawHistoryImageRenderer.Row("20260927267", "22:15", "90333")));

        assertThat(image).contains("width=\"381\" height=\"661\"", "期数", "时间", "成功",
                "268", "22:20", "267", "22:15", "#409eff", "#ff4f82", "#8d3299", "#10c957");
        assertThat(image).doesNotContain("20260927268");
        assertThat(image).contains("data-draw-format=\"3\"")
                .doesNotContain(">龙</text>", ">虎</text>", ">和</text>");
    }

    @Test
    void resultDoesNotShowDragonTigerOrTie() {
        String image = new LotteryDrawHistoryImageRenderer().render(List.of(
                new LotteryDrawHistoryImageRenderer.Row("20260928215", "17:55", "99796")));
        assertThat(image).doesNotContain(">龙</text>", ">虎</text>", ">和</text>");
    }

    @Test
    void legacySnapshotUpgradeIsIdempotentAndPreservesOriginalRows() {
        String image = "<svg data-draw-format=\"2\" xmlns=\"http://www.w3.org/2000/svg\" width=\"381\" height=\"661\">"
                + "<text x=\"14\" y=\"72\">215</text><text x=\"71\" y=\"72\">17:55</text>"
                + "<text x=\"158\" y=\"72\">9</text><text x=\"195\" y=\"72\">9</text>"
                + "<text x=\"232\" y=\"72\">7</text><text x=\"269\" y=\"72\">9</text>"
                + "<text x=\"306\" y=\"72\">6</text>"
                + "<text x=\"343\" y=\"72\" fill=\"#00dd44\">和</text></svg>";
        String upgraded = LotteryDrawHistoryImageRenderer.completeSnapshot(image);
        assertThat(upgraded).contains("data-draw-format=\"3\"", "215", "17:55")
                .doesNotContain(">龙</text>", ">虎</text>", ">和</text>");
        assertThat(LotteryDrawHistoryImageRenderer.completeSnapshot(upgraded)).isEqualTo(upgraded);
        assertThat(upgraded.substring(upgraded.indexOf("<text"), upgraded.indexOf("</svg>")))
                .isEqualTo(image.substring(image.indexOf("<text"), image.indexOf("<text x=\"343\"")));
    }
}
