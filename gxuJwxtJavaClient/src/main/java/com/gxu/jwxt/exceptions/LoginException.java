package com.gxu.jwxt.exceptions;

/** 登录失败 */
public class LoginException extends JwxtException {
    public LoginException(String message) {
        super(message);
    }

    public LoginException(String message, Throwable cause) {
        super(message, cause);
    }
}
