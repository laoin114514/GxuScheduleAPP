package com.gxu.jwxt.module;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.gxu.jwxt.model.PagedResult;
import java.lang.reflect.Type;
import java.util.List;

/** 模块内部的 JSON 反序列化辅助。 */
final class JsonSupport {
    static final Gson GSON = new Gson();

    private JsonSupport() {}

    static <T> PagedResult<T> page(String body, Class<T> itemType) {
        Type type = TypeToken.getParameterized(PagedResult.class, itemType).getType();
        return GSON.fromJson(body, type);
    }

    static <T> List<T> list(String body, Class<T> itemType) {
        Type type = TypeToken.getParameterized(List.class, itemType).getType();
        List<T> result = GSON.fromJson(body, type);
        return result != null ? result : List.of();
    }
}
