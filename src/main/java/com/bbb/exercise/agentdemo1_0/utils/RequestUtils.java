package com.bbb.exercise.agentdemo1_0.common.utils;

import org.springframework.web.util.UriUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 请求参数工具
 */
public class RequestUtils {

    public static final String UTF8_ENC = "UTF-8";

    /** 将请求参数进行升序排序后重新组装 */
    public static String toSortQueryParams(String originQueryParam) {
        List<String> queryParams = new ArrayList<>();

        for (String kv : originQueryParam.split("&")) {
            String[] t = kv.split("=");
            if (t.length > 1) {
                queryParams.add(String.format("%s=%s", UriUtils.decode(t[0], UTF8_ENC), UriUtils.decode(t[1], UTF8_ENC)));
            } else {
                queryParams.add(String.format("%s=", UriUtils.decode(t[0], UTF8_ENC)));
            }
        }
        Collections.sort(queryParams);
        StringBuffer buffer = new StringBuffer();
        for (String queryParm : queryParams) {
            buffer.append(queryParm).append("&");
        }

        return buffer.length() > 0 ? buffer.substring(0, buffer.length() - 2) : StringUtils.EMPTY;
    }
}
