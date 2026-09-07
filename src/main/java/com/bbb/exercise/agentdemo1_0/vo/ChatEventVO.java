package com.bbb.exercise.agentdemo1_0.vo;

import com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天回答内容（SSE 单行事件）：序列化为 JSON 后作为一行 {@code data:} 发送给前端。
 *
 * <p>事件类型与 eventData 的组合见 {@link ChatEventTypeEnum}：
 * DATA 事件 eventData 为文本增量；STOP 事件 eventData 为 null；ERROR 事件 eventData 为错误描述。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatEventVO {

    /** 事件携带的数据 */
    private Object eventData;

    /** 事件类型，见 {@link ChatEventTypeEnum#getValue()} */
    private int eventType;
}
