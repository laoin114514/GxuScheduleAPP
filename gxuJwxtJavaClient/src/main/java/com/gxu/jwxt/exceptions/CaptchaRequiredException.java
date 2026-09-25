package com.gxu.jwxt.exceptions;

/**
 * 教务系统要求输入验证码（登录失败次数超限后服务端强制）。
 *
 * <p>客户端检测到验证码要求（登录页渲染验证码输入框，或错误提示
 * 含「验证码」字样）时抛出；自动抹除浏览器标识重试一次仍失败时
 * 也以本异常终止，避免继续提交触发账号锁定。</p>
 */
public class CaptchaRequiredException extends LoginException {
    public CaptchaRequiredException(String message) {
        super(message);
    }

    public CaptchaRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
