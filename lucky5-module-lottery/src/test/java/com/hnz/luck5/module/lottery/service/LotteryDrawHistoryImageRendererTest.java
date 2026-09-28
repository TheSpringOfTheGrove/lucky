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
    }
}
