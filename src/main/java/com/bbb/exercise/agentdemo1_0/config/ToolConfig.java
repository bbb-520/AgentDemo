package com.bbb.exercise.agentdemo1_0.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 工具装配：自动发现并注册 Agent 可用的全部工具。
 *
 * <p><b>设计目标——新增工具零接线</b>：任何 Spring Bean，只要其方法上标注了
 * {@link Tool @Tool}，就会被自动扫描并转换为 {@link ToolCallback}，
 * 无需在本类或任何配置中显式枚举。新增工具的完整步骤仅为：
 * 「新建一个 {@code @Component}，写一个带 {@code @Tool} 注解的方法」。
 *
 * <p>扫描策略：遍历容器中的 Bean 定义名，先用
 * {@link ApplicationContext#getType(String, boolean)} 做<b>类型级</b>检查
 * （不触发 Bean 提前实例化），命中含 {@code @Tool} 方法的类型后才 {@code getBean}
 * 取出实例交给 {@link MethodToolCallbackProvider}。
 */
@Slf4j
@Configuration
public class ToolConfig {

    /**
     * 汇总容器内所有 {@code @Tool} 方法为统一的工具回调列表。
     * AgentRunner 的决策轮（非流式）与同步对话路径共用这同一批回调。
     */
    @Bean
    public List<ToolCallback> agentToolCallbacks(ApplicationContext applicationContext) {
        List<Object> toolObjects = new ArrayList<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> type = safeGetType(applicationContext, beanName);
            if (type == null || !hasToolMethod(type)) {
                continue;
            }
            try {
                toolObjects.add(applicationContext.getBean(beanName));
            } catch (Exception e) {
                log.warn("[tools] 跳过无法实例化的工具 Bean: {}", beanName, e);
            }
        }

        MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build();
        List<ToolCallback> callbacks = Arrays.asList(provider.getToolCallbacks());
        log.info("[tools] 自动注册工具 {} 个: {}", callbacks.size(),
                callbacks.stream()
                        .map(c -> c.getToolDefinition() == null ? "?" : c.getToolDefinition().name())
                        .toList());
        return callbacks;
    }

    /** 类型级探测（不实例化 FactoryBean），解析失败一律按非工具 Bean 处理 */
    private static Class<?> safeGetType(ApplicationContext context, String beanName) {
        try {
            // getType 返回的已是目标 Class（注意：不可再交给 AopUtils.getTargetClass，
            // 该方法接收的是实例而非 Class，误传 Class 会得到 java.lang.Class）
            return context.getType(beanName, false);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 判断类型（含父类/接口方法）上是否存在 {@code @Tool} 注解的方法 */
    private static boolean hasToolMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (AnnotationUtils.findAnnotation(method, Tool.class) != null) {
                return true;
            }
        }
        return false;
    }
}
