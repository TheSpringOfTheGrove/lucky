package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryMemberAvatarTest {

    @Test
    void currentCustomerHasFiftyDistinctAvatarPaths() {
        Set<String> paths = IntStream.rangeClosed(1, LotteryMemberAvatar.COUNT)
                .mapToObj(LotteryMemberAvatar::path)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(LotteryMemberAvatar.COUNT).isEqualTo(50);
        assertThat(paths).hasSize(50);
        assertThat(LotteryMemberAvatar.path(1)).isEqualTo("player-avatars-chenguanxi/avatar_01.jpg");
        assertThat(LotteryMemberAvatar.path(50)).isEqualTo("player-avatars-chenguanxi/avatar_50.jpg");
        assertThat(LotteryMemberAvatar.path(0)).isEqualTo(LotteryMemberAvatar.path(1));
        assertThat(LotteryMemberAvatar.path(51)).isEqualTo(LotteryMemberAvatar.path(50));
    }
}
