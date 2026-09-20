package com.bbb.exercise.agentdemo1_0.enums;

import lombok.Getter;

/**
 * 聊天事件类型（对外 SSE 契约）
 *
 */
@Getter
public enum ChatEventTypeEnum {

    DATA(1001),                 //数据事件，eventData 为模型输出的文本增量
    STOP(1002),                 //停止事件，流末尾必定发送，eventData 为 null
    ERROR(1004),                //错误事件，eventData 为错误描述
    SESSION_INFO(1010);         //会话信息，流开始时发送一次，eventData 为 JSON 对象

    private final int value;

    ChatEventTypeEnum(int value) {
        this.value = value;
    }
}
