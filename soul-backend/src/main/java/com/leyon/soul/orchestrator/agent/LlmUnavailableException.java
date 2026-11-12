package com.leyon.soul.orchestrator.agent;

public class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException(String msg, Throwable cause) { super(msg, cause); }
    public LlmUnavailableException(String msg) { super(msg); }
}
