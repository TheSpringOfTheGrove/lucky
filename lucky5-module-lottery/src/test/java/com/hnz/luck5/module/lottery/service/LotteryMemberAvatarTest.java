package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryMemberAvatarTest {

    @Test
    void currentCustomerHasEightyDistinctAvatarPaths() {
        Set<String> paths = IntStream.rangeClosed(1, LotteryMemberAvatar.COUNT)
                .mapToObj(LotteryMemberAvatar::path)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(LotteryMemberAvatar.COUNT).isEqualTo(80);
        assertThat(paths).hasSize(80);
        assertThat(LotteryMemberAvatar.path(1)).isEqualTo("player-avatars-chenguanxi/avatar_01.jpg");
        assertThat(LotteryMemberAvatar.path(60)).isEqualTo("player-avatars-chenguanxi/avatar_60.jpg");
        assertThat(LotteryMemberAvatar.path(0)).isEqualTo(LotteryMemberAvatar.path(1));
        assertThat(LotteryMemberAvatar.path(61)).isEqualTo("player-avatars-chenguanxi/avatar_61.jpg");
        assertThat(LotteryMemberAvatar.path(80)).isEqualTo("player-avatars-chenguanxi/avatar_80.jpg");
        assertThat(LotteryMemberAvatar.path(81)).isEqualTo(LotteryMemberAvatar.path(80));
    }
}
