package com.agent.contract;

/**
 * 统一错误码（见 contract-specification.md §6）。
 */
public final class ErrorCodes {

    public static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String IDLE_TIMEOUT = "IDLE_TIMEOUT";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String JSON_OUTPUT_ERROR = "JSON_OUTPUT_ERROR";
    public static final String MODEL_ERROR = "MODEL_ERROR";
    public static final String TRANSPORT_ERROR = "TRANSPORT_ERROR";

    private ErrorCodes() {
    }
}
