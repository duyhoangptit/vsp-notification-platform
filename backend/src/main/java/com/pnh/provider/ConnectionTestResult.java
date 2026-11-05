package com.pnh.provider;

public record ConnectionTestResult(boolean success, String providerError) {
    public static ConnectionTestResult ok() {
        return new ConnectionTestResult(true, null);
    }

    public static ConnectionTestResult failed(String error) {
        return new ConnectionTestResult(false, error);
    }
}
