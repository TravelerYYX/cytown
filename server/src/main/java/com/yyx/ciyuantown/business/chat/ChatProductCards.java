package com.yyx.ciyuantown.business.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.shop.ShopCatalog;
import com.yyx.ciyuantown.common.exception.BizException;

final class ChatProductCards {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ChatProductCards() {}

    static String encode(ShopCatalog.Card card) {
        try { return JSON.writeValueAsString(card); }
        catch (Exception ex) { throw new BizException("商品分享失败，请重试"); }
    }

    static ShopCatalog.Card decode(String content) {
        try { return JSON.readValue(content, ShopCatalog.Card.class); }
        catch (Exception ex) { return null; }
    }

    static String summary(String content) {
        var card = decode(content);
        return "[商品]" + (card == null ? "" : card.name());
    }
}
