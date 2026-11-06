package com.yyx.ciyuantown.business.appauth.service;
public interface EmailCodeService {
    void send(String qqNumber);
    void verify(String qqNumber, String code);
}
