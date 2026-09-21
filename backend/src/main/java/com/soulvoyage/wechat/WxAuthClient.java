package com.soulvoyage.wechat;

/**
 * 微信小程序身份网关抽象（M14，§11.3「微信小程序多端」的服务端入口）。
 *
 * 与 {@link com.soulvoyage.llm.LlmClient}、{@link com.soulvoyage.asr.AsrClient} 同一立场：
 * 只把"外部身份提供方"抽成一个接口，Mock 回放器让契约测试不依赖密钥，
 * 真机切微信只差配置项，代码路径不变。
 *
 * 刻意只做 openid 这一件事：小程序端的用户信息（昵称/头像）由用户自己填，
 * 不取微信资料——减少一次授权弹窗，也避免把第三方资料当成画像数据源。
 */
public interface WxAuthClient {

    /**
     * jscode2session：小程序 {@code wx.login()} 拿到的临时凭证换 openid。
     *
     * @return 该微信在本应用内的假名标识（openid）
     * @throws com.soulvoyage.common.exception.BizException 上游拒绝或不可达时以 1005 收口，不外泄微信原文
     */
    String exchangeOpenid(String jsCode);
}
