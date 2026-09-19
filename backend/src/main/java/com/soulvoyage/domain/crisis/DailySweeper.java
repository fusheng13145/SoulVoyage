package com.soulvoyage.domain.crisis;

import com.soulvoyage.account.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日 04:00（业务时区）危机巡检（下篇·S1 首个定时任务）：
 * CRISIS 届满→COOLING/REVIEW；COOLING 画像达标→NORMAL；顺带扫注销冷静期到期（S2）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySweeper {

    private final CrisisService crisis;
    private final AccountService account;

    @Scheduled(cron = "0 0 4 * * *", zone = "${soulvoyage.business-zone:Asia/Shanghai}")
    public void sweep() {
        try {
            crisis.sweep();
        } catch (Exception e) {
            log.error("crisis lifecycle sweep failed", e);
        }
        try {
            account.processExpiredDeletions();
        } catch (Exception e) {
            log.error("deletion cooling sweep failed", e);
        }
    }
}
