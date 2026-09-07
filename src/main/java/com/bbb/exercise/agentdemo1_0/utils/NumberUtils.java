package com.bbb.exercise.agentdemo1_0.common.utils;

import cn.hutool.core.util.NumberUtil;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数字工具类，继承了 hutool 的 {@link NumberUtil} 并补充项目常用方法。
 */
public class NumberUtils extends NumberUtil {

    /** number 为空返回 0，否则原值 */
    public static Integer null2Zero(Integer number) {
        return number == null ? 0 : number;
    }

    public static Double null2Zero(Double number) {
        return number == null ? 0.0 : number;
    }

    public static Long null2Zero(Long number) {
        return number == null ? 0L : number;
    }

    public static Double setScale(Double number) {
        return new BigDecimal(number).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
    }

    public static boolean equals(Integer number1, Integer number2) {
        if (number1 == null || number2 == null) {
            return false;
        }
        return number1.equals(number2);
    }

    /** 数字除法保留指定小数位 */
    public static Double divToDouble(Integer num1, Integer num2, int scale) {
        if (num2 == null || num2 == 0 || num1 == null || num1 == 0) {
            return 0d;
        }
        return div(num1, num2, scale).doubleValue();
    }

    public static Double max(List<Double> data) {
        if (CollUtils.isEmpty(data)) {
            return null;
        }
        return data.stream().max(Comparator.comparingDouble(num -> num)).orElse(0d);
    }

    public static Double min(List<Double> data) {
        if (CollUtils.isEmpty(data)) {
            return null;
        }
        return data.stream().min(Comparator.comparingDouble(num -> num)).orElse(0d);
    }

    public static Double average(List<Double> data) {
        if (CollUtils.isEmpty(data)) {
            return 0d;
        }
        return data.stream().collect(Collectors.averagingDouble(Double::doubleValue));
    }

    public static Integer toInt(Object obj) {
        return obj == null ? null
                : obj instanceof Integer ? (int) obj : null;
    }

    /** 取绝对值，为 null 时返回 0 */
    public static int abs(Integer number) {
        return number == null ? 0 : Math.abs(number);
    }

    /** 数字格式化字符串，不足位数补 0 */
    public static String repair0(Integer originNumber, Integer digit) {
        StringBuilder number = new StringBuilder(originNumber + "");
        while (number.length() < digit) {
            number.insert(0, "0");
        }
        return number.toString();
    }

    public static String scaleToStr(Integer num, int offset) {
        int m = (int) Math.pow(10, offset);
        int s = num / m;
        int y = num % m;
        if (y == 0) {
            return Integer.toString(s);
        }
        return s + "." + y;
    }
}
