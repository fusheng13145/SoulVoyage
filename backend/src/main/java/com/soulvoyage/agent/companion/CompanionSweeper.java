package com.soulvoyage.agent.companion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 漫聊静默封段扫描（C0：自然日+30min 静默分段）——每 5 分钟扫一遍，测试直调 service.sealSilentSessions() */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanionSweeper {

    private final CompanionService service;

    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void sweep() {
        try {
            int n = service.sealSilentSessions();
            if (n > 0) log.info("companion sweeper sealed {} silent sessions", n);
        } catch (Exception e) {
            log.error("companion sweeper failed", e);
        }
    }
}
