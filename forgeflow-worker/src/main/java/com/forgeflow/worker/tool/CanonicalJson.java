package com.forgeflow.worker.tool;

import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stable args hash: same arguments in a different map order produce the same approval key.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String of(Map<String, Object> args) {
        return JSON.toJSONString(sort(args));
    }

    private static Object sort(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), sort(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> values = new ArrayList<>(list.size());
            for (Object item : list) {
                values.add(sort(item));
            }
            return values;
        }
        return value;
    }
}
