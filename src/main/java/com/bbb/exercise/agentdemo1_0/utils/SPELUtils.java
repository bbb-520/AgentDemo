package com.bbb.exercise.agentdemo1_0.common.utils;

import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SPEL 表达式工具：将模板中的 #{expr} 占位符替换为参数值
 */
public class SPELUtils {

    /** 将模板中的表达式替换成 args 参数中的值 */
    public static String parse(String formatter, String[] paraNameArr, Object[] args) {
        if (StringUtils.isNotBlank(formatter) && formatter.indexOf("#") > -1) {
            Pattern pattern = Pattern.compile("(\\#\\{([^\\}]*)\\})");
            Matcher matcher = pattern.matcher(formatter);
            List<String> keys = new ArrayList<>();
            while (matcher.find()) {
                keys.add(matcher.group());
            }
            if (!CollUtils.isEmpty(keys)) {
                ExpressionParser parser = new SpelExpressionParser();
                StandardEvaluationContext context = new StandardEvaluationContext();
                for (int i = 0; i < paraNameArr.length; i++) {
                    context.setVariable(paraNameArr[i], args[i]);
                }

                for (String tmp : keys) {
                    formatter = formatter.replace(tmp,
                            parser.parseExpression("#" + tmp.substring(2, tmp.length() - 1)).getValue(context, String.class));
                }
                return formatter;
            }
        }
        return null;
    }
}
