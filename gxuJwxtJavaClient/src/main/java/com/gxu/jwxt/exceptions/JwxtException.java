package com.gxu.jwxt.exceptions;

/** client 基础异常 */
public class JwxtException extends RuntimeException {
    public JwxtException(String message) {
        super(message);
    }

    public JwxtException(String message, Throwable cause) {
        super(message, cause);
    }
}
