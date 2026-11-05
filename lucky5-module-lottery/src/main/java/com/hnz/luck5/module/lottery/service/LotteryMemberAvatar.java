package com.hnz.luck5.module.lottery.service;

import java.util.concurrent.ThreadLocalRandom;

/** Shared avatar choices for manually and automatically created members. */
final class LotteryMemberAvatar {

    static final int COUNT = 60;

    private LotteryMemberAvatar() {
    }

    static int randomAvatar() {
        return ThreadLocalRandom.current().nextInt(1, COUNT + 1);
    }

    static String path(int avatar) {
        int number = Math.max(1, Math.min(COUNT, avatar));
        return "player-avatars-chenguanxi/avatar_" + (number < 10 ? "0" : "") + number + ".jpg";
    }
}
