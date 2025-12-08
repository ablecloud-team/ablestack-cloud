package org.apache.cloudstack.wallAlerts.exception;

public class WallApiException extends RuntimeException {
    public WallApiException(String message) { super(message); }
    public WallApiException(String message, Throwable cause) { super(message, cause); }
}
