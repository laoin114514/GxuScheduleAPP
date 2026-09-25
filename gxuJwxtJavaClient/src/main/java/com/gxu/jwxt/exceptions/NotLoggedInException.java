package com.gxu.jwxt.exceptions;

/** 未登录时调用需认证接口 */
public class NotLoggedInException extends JwxtException {
    public NotLoggedInException() {
        super("请先调用 login() 登录");
    }
}
