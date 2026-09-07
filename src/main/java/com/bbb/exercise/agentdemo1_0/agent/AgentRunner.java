package com.bbb.exercise.agentdemo1_0.agent;

import com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum;
import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import com.bbb.exercise.agentdemo1_0.utils.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunner {

    /** 推理行前缀：模型在调用工具前输出的一行说明，会被剥离为 REASONING 事件、不进入最终回答 */
    static final String THINK_PREFIX = "思考：";

    /** 防止模型陷入「无限调用工具」的死循环（含参数非法后的自我修正轮） */
    private static final int MAX_TOOL_ROUNDS = 5;

    /** TOOL_CALL_RESULT 事件携带的结果截断长度（完整结果仍原样回传模型） */
    private static final int RESULT_EVENT_MAX_LEN = 500;

    /** 答案回放的单块字符数与节奏：兼顾渐进渲染体验与整体时延 */
    private static final int ANSWER_CHUNK_SIZE = 12;
    private static final Duration ANSWER_REPLAY_INTERVAL = Duration.ofMillis(30);

    /** 模型最终未产出可用答案时的兜底文案（含工具调用阶段异常等） */
    //TODO 模型最终为产出则调用LLM进行兜底
    private static final String FIXED_FALLBACK =
            "抱歉，本次回复因工具调用阶段异常未能正常生成。请稍后再试，或换个问法。";

    /** 线程级只读：用于校验模型输出的 {@code arguments} 是否是合法 JSON */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 高层 ChatClient（同步路径）：默认系统提示 / Advisor / 工具已由 AiConfiguration 配好 */
    private final ChatClient chatClient;

    /** 底层 ChatModel（流式两阶段决策轮直接调用，手动工具循环） */
    private final ChatModel chatModel;

    /** 会话记忆（多轮上下文；与记忆顾问共用同一 Bean） */
    private final ChatMemory chatMemory;

    /** 自动注册的全部工具回调（config/ToolConfig 扫描装配） */
    private final List<ToolCallback> toolCallbacks;

    /** 系统提示词（config/AiConfiguration 读取 classpath:system_prompt 暴露的 Bean） */
    private final String systemPrompt;

    /**
     * 阻塞式对话（供命令行演示与同步接口使用；走 ChatClient 默认工具执行 + 记忆顾问）
     *
     * <p>系统提示、日志/记忆 Advisor、工具列表均为 ChatClient 的默认配置，
     * 此处仅需指定会话 id 与用户问题。
     *
     * @param question       用户问题
     * @param conversationId 会话 id，为空则用默认会话
     * @return 最终答案
     */
    public String chat(String question, String conversationId) {
        log.info("[agent] 收到同步请求 sessionId={} question={}", conversationId, question);
        long start = System.currentTimeMillis();
        ChatResponse response = chatClient.prompt()
                .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(question)
                .call()
                .chatResponse();
        String answer = response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                ? "" : response.getResult().getOutput().getText();
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        Integer prompt = usage == null ? null : usage.getPromptTokens();
        Integer completion = usage == null ? null : usage.getCompletionTokens();
        log.info("[agent] 完成 sessionId={} 耗时={}ms tokens=prompt={},completion={} err=null",
                conversationId, System.currentTimeMillis() - start, prompt, completion);
        return answer;
    }

    /**
     * 流式对话直播（供 SSE 接口使用）。
     *
     * <p>返回统一的事件流，事件顺序：
     * SESSION_INFO → (REASONING | TOOL_CALL_STARTED → TOOL_CALL_RESULT/TOOL_CALL_FAILED)*
     * → DATA(最终答案，分块回放) → USAGE。STOP(1002) 由服务层在末尾补发，不在此流内。
     *
     * @param question       用户问题
     * @param conversationId 内部会话键（形如 {@code chat-xxx}）
     * @return 直播事件流，每个元素为一个 {@link ChatEventVO}
     */
    public Flux<ChatEventVO> stream(String question, String conversationId) {
        log.info("[agent] 收到流式请求 sessionId={} question={}", conversationId, question);
        long start = System.currentTimeMillis();
        AtomicReference<AgentTurn> holder = new AtomicReference<>();
        return Flux.defer(() -> {
                    // 每次（重新）订阅都构建全新上下文，避免状态串扰
                    AgentTurn turn = new AgentTurn(conversationId, question, start);
                    holder.set(turn);
                    //concat：保证流的顺序
                    return Flux.concat(
                            sessionInfoEvent(turn),         //起始事件
                            runTurn(turn, 0),     //核心多轮循环
                            usageEvent(turn));              //结束统计事件
                })
                .doOnComplete(() -> {
                    AgentTurn turn = holder.get();
                    log.info("[agent] 完成 sessionId={} 耗时={}ms tokens=prompt={},completion={} err=null",
                            conversationId, System.currentTimeMillis() - start,
                            turn.promptTokens.get(), turn.completionTokens.get());
                })
                .doOnError(err -> log.error("[agent] 失败 sessionId={} 耗时={}ms err={}",
                        conversationId, System.currentTimeMillis() - start, err.toString()));
    }

    /**
     * 输出被中途取消/停止时，把已生成的部分内容写入会话记忆，
     * 避免多轮对话的上下文出现断层。
     * TODO 存储到Redis中
     */
    public void savePartialResponse(String conversationId, String content) {
        if (StringUtils.isBlank(content)) {
            return;
        }
        chatMemory.add(conversationId, List.of(new AssistantMessage(content)));
    }

    /** 读取某个会话的历史消息 */
    public List<Message> history(String conversationId) {
        return chatMemory.get(conversationId);
    }

    /** 清空某个会话的历史消息 */
    public void clearHistory(String conversationId) {
        chatMemory.clear(conversationId);
    }

    // =====================================================================
    // 以下为两阶段直播的内部实现
    // =====================================================================

    /** 一次用户请求的上下文（跨多个工具轮次共享） */
    private static final class AgentTurn {
        final String conversationId;
        final String question;
        final long startMillis;
        /** 上下文消息：system + 历史 + user，工具轮次会继续追加 assistant/tool 消息 */
        final List<Message> messages = new ArrayList<>();
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong completionTokens = new AtomicLong();

        AgentTurn(String conversationId, String question, long startMillis) {
            this.conversationId = conversationId;
            this.question = question;
            this.startMillis = startMillis;
        }
    }

    /**
     * 装载一次请求的完整上下文：system 提示 + 会话历史 + 本次问题。
     *
     * <p><b>与记忆顾问等价</b>：此处的「从 {@link ChatMemory} 取出历史并置于用户问题之前」
     * 正是 {@code MessageChatMemoryAdvisor#before} 的行为。因流式决策轮绕过 ChatClient
     * 的 Advisor 管线（原因见类 Javadoc），故以显式方法复刻其语义，保证两条路径记忆一致。
     */
    private void loadConversationContext(AgentTurn turn) {
        turn.messages.add(new SystemMessage(systemPrompt));
        List<Message> history = chatMemory.get(turn.conversationId);
        if (history != null) {
            turn.messages.addAll(new ArrayList<>(history));
        }
        turn.messages.add(new UserMessage(turn.question));
    }

    /**
     * 驱动「非流式决策 → 可能调用工具」的多轮循环。
     *
     * <p>每一轮：{@code ChatModel#call} 拿到完整响应 → 文本经「思考：」行剥离为
     * REASONING / DATA → 若声明了工具调用则执行并递归下一轮；否则视为最终答案，
     * 分块回放并回写会话记忆后结束。决策轮是阻塞 HTTP 调用，整体挪到弹性线程池，
     * 避免阻塞事件循环线程。
     */
    private Flux<ChatEventVO> runTurn(AgentTurn turn, int roundIndex) {
        if (roundIndex >= MAX_TOOL_ROUNDS) {
            log.warn("[agent] 工具调用轮次超过上限 sessionId={} rounds={}", turn.conversationId, MAX_TOOL_ROUNDS);
            return Flux.just(buildEvent(ChatEventTypeEnum.ERROR,
                    "工具调用轮次超过 " + MAX_TOOL_ROUNDS + " 次，已终止本次执行。"));
        }
        // 首次进入时初始化上下文
        if (roundIndex == 0) {
            loadConversationContext(turn);
        }

        //创建本轮专用上下文 RoundCtx
        RoundCtx ctx = new RoundCtx();
        //返回延迟执行的 Flux
        return Flux.defer(() -> {
            // —— 决策轮：非流式，tool_call 参数必然完整 ——
            ChatResponse decision = chatModel.call(new Prompt(turn.messages, decisionOptions()));
            accumulateUsage(decision, turn);

            //提取模型的输出消息
            AssistantMessage output = (decision == null || decision.getResult() == null)
                    ? null : decision.getResult().getOutput();

            //提取模型在调用工具前可能输出的一些说明文字（例如“我需要查询天气”），或者是最终答案。
            String text = output == null ? null : output.getText();

            //处理模型输出的文本
            List<ChatEventVO> events = new ArrayList<>();
            if (StringUtils.isNotEmpty(text)) {
                events.addAll(ctx.splitter.feed(text));     //对模型输出的纯文本进行增量解析
                events.addAll(ctx.splitter.flush());
            }

            //提取工具调用列表并分支
            List<AssistantMessage.ToolCall> toolCalls =
                    (output != null && output.hasToolCalls()) ? output.getToolCalls() : List.of();

            if (toolCalls.isEmpty()) {
                // —— 最终答案轮：DATA 分块回放 + 回写记忆 ——
                return finalAnswerFlow(turn, events);
            }

            // —— 工具调用轮 ——
            //将 assistant 消息加入上下文
            turn.messages.add(new AssistantMessage(StringUtils.toString(text), Map.of(), toolCalls));
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>(toolCalls.size());
            //遍历执行每个工具调用
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                //这一步产生的事件被添加到 events 列表中，稍后统一发出。
                events.addAll(executeTool(toolCall, ctx, toolResponses));
            }
            //将工具结果加入上下文
            turn.messages.add(new ToolResponseMessage(toolResponses));

            //返回本轮事件 + 递归调用下一轮
            return Flux.concat(Flux.fromIterable(events),       //将本轮收集到的所有事件作为一个 Flux 发出。
                    Flux.defer(() -> runTurn(turn, roundIndex + 1)));       //递归调用 runTurn 进入下一轮决策
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 组装决策轮请求选项：关闭模型内部工具执行，由本类接管循环以产出直播事件。
     *
     * <p>注意：{@code internalToolExecutionEnabled(false)} 是手动工具循环的<b>协议级要求</b>
     * （否则模型会自行串行执行工具、AgentRunner 拿不到逐轮事件），因此不放入
     * 可配置的默认选项（{@code OpenAiProperties}），仅在此按需附加工具回调。
     */
    private ChatOptions decisionOptions() {
        return OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(toolCallbacks.toArray(new ToolCallback[0]))
                .build();
    }

    /**
     * 最终答案轮：把本轮文本事件中的 DATA 汇总为完整答案，按小块带节奏回放，
     * 并回写会话记忆（仅存最终 user + assistant，不含中间工具消息，与记忆顾问语义一致）。
     */
    private Flux<ChatEventVO> finalAnswerFlow(AgentTurn turn, List<ChatEventVO> textEvents) {
        List<ChatEventVO> head = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        for (ChatEventVO event : textEvents) {
            if (event.getEventType() == ChatEventTypeEnum.DATA.getValue()
                    && event.getEventData() instanceof String s) {
                answer.append(s);
            } else {
                head.add(event);
            }
        }

        String finalText = StringUtils.isBlank(answer.toString()) ? FIXED_FALLBACK : StringUtils.trim(answer.toString());
        if (finalText.equals(FIXED_FALLBACK)) {
            log.warn("[agent] 模型未输出最终答案，使用兜底文字 sessionId={}", turn.conversationId);
        }
        try {
            chatMemory.add(turn.conversationId,
                    List.of(new UserMessage(turn.question), new AssistantMessage(finalText)));
        } catch (Exception e) {
            log.error("[agent] 写入会话记忆失败 sessionId={}", turn.conversationId, e);
        }

        return Flux.concat(
                Flux.fromIterable(head),
                Flux.fromIterable(chunkAnswer(finalText))
                        .delayElements(ANSWER_REPLAY_INTERVAL));
    }

    /** 把完整答案切成小粒度 DATA 事件，模拟逐段输出的渲染体验 */
    private static List<ChatEventVO> chunkAnswer(String text) {
        List<ChatEventVO> chunks = new ArrayList<>((text.length() / ANSWER_CHUNK_SIZE) + 1);
        for (int i = 0; i < text.length(); i += ANSWER_CHUNK_SIZE) {
            chunks.add(dataEvent(text.substring(i, Math.min(i + ANSWER_CHUNK_SIZE, text.length()))));
        }
        if (chunks.isEmpty()) {
            chunks.add(dataEvent(text));
        }
        return chunks;
    }

    /** 决策轮 usage 累加进全局统计 */
    private static void accumulateUsage(ChatResponse response, AgentTurn turn) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        if (usage.getPromptTokens() != null) {
            turn.promptTokens.addAndGet(usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() != null) {
            turn.completionTokens.addAndGet(usage.getCompletionTokens());
        }
    }

    /**
     * 执行单个工具调用并产出直播事件（同步方法，返回事件列表）。
     *
     * @param toolCall          模型输出中的一个工具调用请求，包含工具名称、参数 JSON、唯一 ID 等。
     * @param ctx               本轮决策的上下文，其中包含 ReasoningSplitter，用于判断模型是否已经输出过“思考：”行。
     * @param toolResponses     收集当前轮所有工具调用的响应，每个工具调用都必须对应一个 ToolResponse，以保证回传模型的消息格式正确
     * @return                  返回值是一个 List<ChatEventVO>，包含本次工具调用过程中需要发送给前端的所有事件。
     */
    private List<ChatEventVO> executeTool(AssistantMessage.ToolCall toolCall, RoundCtx ctx,
                                          List<ToolResponseMessage.ToolResponse> toolResponses) {
        List<ChatEventVO> events = new ArrayList<>(4);
        // 若模型本轮没有输出真实的「思考：」行，则按固定格式自动补一条 REASONING
        if (!ctx.splitter.isThinkingEmitted()) {
            events.add(buildEvent(ChatEventTypeEnum.REASONING, "调用工具：" + toolCall.name()));
        }
        // 完整工具调用生命周期：必须先发 STARTED，再视情况发 RESULT / FAILED
        events.add(buildEvent(ChatEventTypeEnum.TOOL_CALL_STARTED, Map.of(
                "toolCallId", StringUtils.toString(toolCall.id()),
                "toolName", StringUtils.toString(toolCall.name()),
                "arguments", StringUtils.toString(toolCall.arguments()))));

        // 防御分支：字段不全或 arguments 不是合法 JSON —— 错误回传模型，让其下一轮修正重试
        if (!isCompleteToolCall(toolCall)) {
            log.warn("[agent] 工具调用参数不合法 id={} name={} args={}",
                    StringUtils.toString(toolCall.id()),
                    StringUtils.toString(toolCall.name()),
                    StringUtils.truncate(toolCall.arguments(), 80));
            String error = "工具调用失败：参数不是合法 JSON（可能被输出长度截断）。";
            events.add(buildEvent(ChatEventTypeEnum.TOOL_CALL_FAILED, Map.of(
                    "toolName", StringUtils.toString(toolCall.name()), "error", error)));
            toolResponses.add(new ToolResponseMessage.ToolResponse(
                    StringUtils.toString(toolCall.id()), StringUtils.toString(toolCall.name()),
                    error + "请重新调用本工具，输出完整且精简的参数 JSON。"));
            return events;
        }

        //根据工具名称在 toolCallbacks 列表中查找对应的 ToolCallback
        ToolCallback callback = findToolCallback(toolCall.name());
        if (callback == null) {
            String error = "未找到可用的工具：" + toolCall.name();
            log.warn("[agent] 工具未注册 tool={}", toolCall.name());
            events.add(buildEvent(ChatEventTypeEnum.TOOL_CALL_FAILED, Map.of(
                    "toolName", StringUtils.toString(toolCall.name()), "error", error)));
            toolResponses.add(new ToolResponseMessage.ToolResponse(
                    toolCall.id(), toolCall.name(), "工具调用失败：" + error));
            return events;
        }

        //执行工具调用
        try {
            log.info("[agent] 调用工具 tool={} args={}", toolCall.name(), toolCall.arguments());

            String result = callback.call(toolCall.arguments());
            log.info("[agent] 工具返回 tool={} len={}", toolCall.name(),
                    result == null ? 0 : result.length());

            //发送 TOOL_CALL_RESULT 事件
            events.add(buildEvent(ChatEventTypeEnum.TOOL_CALL_RESULT, Map.of(
                    "toolName", StringUtils.toString(toolCall.name()),
                    "result", StringUtils.truncate(result, RESULT_EVENT_MAX_LEN))));
            toolResponses.add(new ToolResponseMessage.ToolResponse(
                    toolCall.id(), toolCall.name(), result));
        } catch (Exception e) {
            log.error("[agent] 工具执行失败 tool={}", toolCall.name(), e);
            String error = e.getMessage() == null ? e.toString() : e.getMessage();
            events.add(buildEvent(ChatEventTypeEnum.TOOL_CALL_FAILED, Map.of(
                    "toolName", StringUtils.toString(toolCall.name()), "error", error)));
            // 把错误回传模型而非中断流，模型可据此生成兜底回答
            toolResponses.add(new ToolResponseMessage.ToolResponse(
                    toolCall.id(), toolCall.name(), "工具执行失败：" + error));
        }
        return events;
    }

    /** 按工具名查找已注册的 {@link ToolCallback} */
    private ToolCallback findToolCallback(String name) {
        if (name == null || toolCallbacks == null) {
            return null;
        }
        for (ToolCallback callback : toolCallbacks) {
            String n = callback.getToolDefinition() == null ? null : callback.getToolDefinition().name();
            if (name.equals(n)) {
                return callback;
            }
        }
        return null;
    }

    /**
     * 判断一个 {@link AssistantMessage.ToolCall} 是否可被安全调用：
     * <ul>
     *     <li>id / name / arguments 均非空白；</li>
     *     <li>{@code arguments} 可以被 Jackson 解析为合法 JSON。</li>
     * </ul>
     *
     * <p>被设计为防御性兜底：即便上游出现脏数据，到达 {@code executeTool}
     * 时也应该走失败分支，而非把脏数据丢给 Spring AI 的反序列化层。
     */
    static boolean isCompleteToolCall(AssistantMessage.ToolCall tc) {
        if (tc == null) {
            return false;
        }
        if (StringUtils.isBlank(tc.id()) || StringUtils.isBlank(tc.name()) || StringUtils.isBlank(tc.arguments())) {
            return false;
        }
        try {
            JSON.readTree(tc.arguments());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 事件构造 ----------------

    private static ChatEventVO buildEvent(ChatEventTypeEnum type, Object data) {
        return ChatEventVO.builder()
                .eventType(type.getValue())
                .eventData(data)
                .build();
    }

    private static ChatEventVO dataEvent(String text) {
        return buildEvent(ChatEventTypeEnum.DATA, text);
    }

    private static ChatEventVO reasoningEvent(String text) {
        return buildEvent(ChatEventTypeEnum.REASONING, text);
    }

    /** 会话元信息事件：流开始处推送一次 */
    private static Flux<ChatEventVO> sessionInfoEvent(AgentTurn turn) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", turn.conversationId);
        data.put("timestamp", System.currentTimeMillis());
        return Flux.just(buildEvent(ChatEventTypeEnum.SESSION_INFO, data));
    }

    /** 用量统计事件：流正常结束时（STOP 之前）推送一次 */
    private static Flux<ChatEventVO> usageEvent(AgentTurn turn) {
        return Flux.defer(() -> {
            long prompt = turn.promptTokens.get();
            long completion = turn.completionTokens.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("promptTokens", prompt);
            data.put("completionTokens", completion);
            data.put("totalTokens", prompt + completion);
            data.put("durationMs", System.currentTimeMillis() - turn.startMillis);
            return Flux.just(buildEvent(ChatEventTypeEnum.USAGE, data));
        });
    }

    /**
     * 「思考：」增量文本解析器：对模型输出按行分类。
     * 专门用于从模型输出的纯文本中识别并分离以 "思考：" 开头的推理行（REASONING）和普通正文内容（DATA）。
     * 由于模型输出可能是分块到达的（尽管在 runTurn 中目前是一次性传入完整文本，但该类设计上支持增量处理），
     * 它采用了一个字符级状态机来保证即使在任意位置切分文本，也不会丢失字符或错误识别前缀。
     */
    static final class ReasoningSplitter {
        // 暂存行首尚未判定的字符
        private final StringBuilder lineHead = new StringBuilder();
        // 当前是否正处于一个推理行内部
        private boolean inThinking;
        // 推理行前缀之后的内容累积
        private final StringBuilder thinking = new StringBuilder();
        // 本轮是否已发出过真实推理行
        private boolean thinkingEmitted;
        // 已判定为正文、等待发出的字符缓冲
        private final StringBuilder data = new StringBuilder();

        /**
         * 增量输入处理
         * 接收一段文本 delta，返回需要立即发出的事件列表
         */
        List<ChatEventVO> feed(String delta) {
            List<ChatEventVO> events = new ArrayList<>();
            for (int i = 0; i < delta.length(); i++) {
                char c = delta.charAt(i);
                if (inThinking) {
                    if (c == '\n') {
                        flushThinking(events);
                    } else {
                        thinking.append(c);
                    }
                    continue;
                }
                if (c == '\n') {
                    // 行在判定完成前结束（空行/普通行）：行首缓冲整体按正文放行
                    data.append(lineHead).append(c);
                    lineHead.setLength(0);
                    continue;
                }
                lineHead.append(c);
                if (lineHead.length() == THINK_PREFIX.length()) {
                    if (THINK_PREFIX.contentEquals(lineHead)) {
                        // 命中「思考：」前缀：进入推理行，之前累积的正文先放行
                        emitData(events);
                        inThinking = true;
                        lineHead.setLength(0);
                    } else {
                        data.append(lineHead);
                        lineHead.setLength(0);
                    }
                } else if (!THINK_PREFIX.startsWith(lineHead.toString())) {
                    // 前 1~2 个字符已不可能命中前缀（如正文以「北」开头）
                    data.append(lineHead);
                    lineHead.setLength(0);
                }
            }
            emitData(events);
            return events;
        }

        /**
         * 处理残留内容
         * 当一段完整的模型输出文本处理完毕（例如 runTurn 中一次性获取到完整响应后，
         * 调用 feed 再调用 flush）时，需要处理可能残留的状态
         * 确保没有字符丢失
         */
        List<ChatEventVO> flush() {
            List<ChatEventVO> events = new ArrayList<>();
            if (inThinking) {
                flushThinking(events);
            } else if (lineHead.length() > 0) {
                // 无法证明是推理行的行首残留一律按正文放行，避免丢字
                data.append(lineHead);
                lineHead.setLength(0);
            }
            emitData(events);
            return events;
        }

        /** 是否已发出真实推理行（供自动补 REASONING 时去重） */
        boolean isThinkingEmitted() {
            return thinkingEmitted;
        }

        /** 推理行遇到换行/文本末尾：去掉前缀后整行作为一条 REASONING 发出 */
        private void flushThinking(List<ChatEventVO> events) {
            String content = StringUtils.trim(thinking.toString());
            if (StringUtils.isNotEmpty(content)) {
                thinkingEmitted = true;
                events.add(reasoningEvent(content));
            }
            thinking.setLength(0);
            inThinking = false;
        }

        private void emitData(List<ChatEventVO> events) {
            if (data.length() > 0) {
                String s = data.toString();
                events.add(dataEvent(s));
                data.setLength(0);
            }
        }
    }

    /** 单轮决策状态 */
    private static final class RoundCtx {
        /** 「思考：」行分类器 */
        final ReasoningSplitter splitter = new ReasoningSplitter();
    }
}
