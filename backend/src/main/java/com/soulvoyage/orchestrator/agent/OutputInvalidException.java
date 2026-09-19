package com.soulvoyage.orchestrator.agent;

public class OutputInvalidException extends RuntimeException {
    public OutputInvalidException(String msg) { super(msg); }
    public OutputInvalidException(String msg, Throwable cause) { super(msg, cause); }
}
