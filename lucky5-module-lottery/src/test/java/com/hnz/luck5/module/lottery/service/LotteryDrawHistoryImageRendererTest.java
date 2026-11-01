package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryDrawHistoryImageRendererTest {

    @Test
    void shouldRenderSelectedCardStyleWithOriginalNumbersAndTimes() {
        String image = new LotteryDrawHistoryImageRenderer().render(List.of(
                new LotteryDrawHistoryImageRenderer.Row("20260927268", "22:20", "86165"),
                new LotteryDrawHistoryImageRenderer.Row("20260927267", "22:15", "90333")));

        assertThat(image).contains("width=\"381\" height=\"661\"", "次数", "时间", "入住房号",
                "268", "22:20", "267", "22:15", "#409eff", "#ff4f82", "#8d3299", "#10c957");
        assertThat(image).doesNotContain("20260927268");
        assertThat(image).contains("data-draw-format=\"3\"", "龙", "#da3975", "rx=\"9\"");
    }

    @Test
    void resultUsesTheFourthBallNotTheFifth() {
        String image = new LotteryDrawHistoryImageRenderer().render(List.of(
                new LotteryDrawHistoryImageRenderer.Row("20260928215", "17:55", "99796")));
        assertThat(image).contains(">和</text>", "#1f9e61").doesNotContain(">龙</text>");
    }

    @Test
    void legacySnapshotUpgradeIsIdempotentAndPreservesOriginalRows() {
        String image = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"381\" height=\"661\">"
                + "<text x=\"14\" y=\"72\">215</text><text x=\"71\" y=\"72\">17:55</text>"
                + "<text x=\"158\" y=\"72\">9</text><text x=\"195\" y=\"72\">9</text>"
                + "<text x=\"232\" y=\"72\">7</text><text x=\"269\" y=\"72\">9</text>"
                + "<text x=\"306\" y=\"72\">6</text></svg>";
        String upgraded = LotteryDrawHistoryImageRenderer.completeSnapshot(image);
        assertThat(upgraded).contains("次数", "入住房号", "215", "17:55", ">和</text>", "data-draw-format=\"3\"");
        assertThat(LotteryDrawHistoryImageRenderer.completeSnapshot(upgraded)).isEqualTo(upgraded);
        assertThat(upgraded).contains(">9</text>", ">7</text>", ">6</text>");
        assertThat(LotteryDrawHistoryImageRenderer.completeSnapshot(
                image.replace("<svg ", "<svg data-draw-format=\"2\" "))).isEqualTo(upgraded);
    }
}
