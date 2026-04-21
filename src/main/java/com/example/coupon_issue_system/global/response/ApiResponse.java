package com.example.coupon_issue_system.global.response;

public record ApiResponse<T>(
        boolean success,
        String message,
        T data
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<T>(true, "success", data);
    }

    public static <T> ApiResponse<T> failure(String message) {
        return new ApiResponse<T>(false, message, null);
    }
}
