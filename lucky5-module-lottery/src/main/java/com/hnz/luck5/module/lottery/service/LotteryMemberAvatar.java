package com.hnz.luck5.module.lottery.service;

import java.util.concurrent.ThreadLocalRandom;

/** Shared avatar choices for manually and automatically created members. */
final class LotteryMemberAvatar {

    static final int COUNT = 35;

    private LotteryMemberAvatar() {
    }

    static int randomAvatar() {
        return ThreadLocalRandom.current().nextInt(1, COUNT + 1);
    }

    static String path(int avatar) {
        return switch (avatar) {
            case 1 -> "player-avatars/avatar_1039.jpg";
            case 2 -> "player-avatars/avatar_173.jpg";
            case 3 -> "player-avatars/avatar_175.jpg";
            case 4 -> "player-avatars/avatar_207.jpg";
            case 5 -> "player-avatars/avatar_336.jpg";
            case 6 -> "player-avatars/avatar_339.jpg";
            case 7 -> "player-avatars/avatar_343.jpg";
            case 8 -> "player-avatars/avatar_792.jpg";
            case 9 -> "player-avatars/avatar_939.jpg";
            default -> "player-avatars/avatar_" + Math.max(10, Math.min(COUNT, avatar)) + ".png";
        };
    }
}
