package com.soulvoyage.orchestrator;

/** 调度中心与五大 Agent 的身份编码（手册 §3.4） */
public enum AgentCode {
    ORCHESTRATOR,
    EMOTION,        // 情绪感知
    TRACE,          // 溯源推理
    SIMULATE,       // 心智训练
    SUPPORT,        // 疏导干预
    RISK_ARCHIVE;   // 风险研判&归档

    public static final String ALL = "ORCHESTRATOR,EMOTION,TRACE,SIMULATE,SUPPORT,RISK_ARCHIVE";
}
