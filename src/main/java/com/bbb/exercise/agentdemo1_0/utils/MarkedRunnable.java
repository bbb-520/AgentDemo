package com.bbb.exercise.agentdemo1_0.common.utils;

import org.slf4j.MDC;

import java.util.Map;

/**
 * 携带 MDC 上下文的可运行任务：将创建时的 MDC 上下文传递到异步线程执行，
 * 执行结束后清理，避免日志链路追踪信息丢失。
 */
public class MarkedRunnable implements Runnable {

    private final Runnable runnable;
    private final Map<String, String> context;

    public MarkedRunnable(Runnable runnable) {
        this.runnable = runnable;
        this.context = MDC.getCopyOfContextMap();
    }

    @Override
    public void run() {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
        try {
            runnable.run();
        } finally {
            MDC.clear();
        }
    }
}
