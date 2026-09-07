package com.bbb.exercise.agentdemo1_0.enums;

import lombok.Getter;

/**
 * 聊天事件类型（对外 SSE 契约，对齐参考实现的协议并扩展错误/直播类事件）
 *
 */
@Getter
public enum ChatEventTypeEnum {

    DATA(1001),                     //数据事件，eventData 为模型输出的文本增量
    STOP(1002),                     //停止事件，流末尾必定发送，eventData 为 null
    PARAM(1003),                    //参数事件（预留，目前不使用）
    ERROR(1004),                    //错误事件（本项目扩展），eventData 为错误描述
    TOOL_CALL_STARTED(1005),        //工具调用开始（直播扩展），eventData 为 JSON 对象
    TOOL_CALL_RESULT(1006),         //工具调用返回（直播扩展），eventData 为 JSON 对象
    REASONING(1007),                //模型推理摘要（直播扩展），eventData 为纯文本
    TOOL_CALL_FAILED(1008),         //工具调用失败（直播扩展），eventData 为 JSON 对象
    USAGE(1009),                    //用量统计（直播扩展），eventData 为 JSON 对象
    SESSION_INFO(1010);             //会话信息（直播扩展），流开始时发送一次，eventData 为 JSON 对象

    private final int value;

    ChatEventTypeEnum(int value) {
        this.value = value;
    }
}
