package com.gogo.travel.agent.session;

import com.gogo.travel.agent.registry.AgentExecutionRegistry;
import com.gogo.travel.agent.registry.SseEmitterRegistry;
import com.gogo.travel.agent.service.ChatSseNotifier;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 基于 Redis Pub/Sub 的 Agent 优雅中断广播组件。
 *
 * <p>集群模式下，用户的 interrupt 请求可能落在任意节点上，而目标 Agent 实际运行在
 * 另一个节点。本组件让每个节点订阅统一的 {@code agent:interrupt} 频道；收到消息后
 * 每个节点执行完整的本地清理：
 * <ol>
 *   <li>{@link AgentExecutionRegistry#interruptLocal(String)} —— 中断本地运行中的 Agent 并持久化 memory</li>
 *   <li>{@link AgentSessionManager#remove(String)} —— 清理本地缓存的 ask_user suspended 状态</li>
 *   <li>{@link PendingToolSessionStore#clear(String)} —— 清理共享 Session 中的 pending tool 状态</li>
 *   <li>若本节点持有 SseEmitter，发送 interrupted 事件通知前端</li>
 * </ol>
 * 所有操作均为幂等，状态不存在时自动跳过，确保集群中每个节点都尝试清理自己可能持有的状态。</p>
 *
 * @author Hollis
 */
@Component
public class AgentInterruptBroadcast implements MessageListener {

    private static final Logger logger = LoggerFactory.getLogger(AgentInterruptBroadcast.class);

    public static final String INTERRUPT_CHANNEL = "agent:interrupt";

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private AgentExecutionRegistry executionRegistry;
    @Autowired
    private RedisMessageListenerContainer listenerContainer;
    @Autowired
    private AgentSessionManager agentSessionManager;
    @Autowired
    private PendingToolSessionStore pendingToolSessionStore;
    @Autowired
    private SseEmitterRegistry emitterRegistry;
    @Autowired
    private ChatSseNotifier sseNotifier;

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(INTERRUPT_CHANNEL));
        logger.info("[INTERRUPT_BROADCAST] 已订阅 Redis 频道: {}", INTERRUPT_CHANNEL);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String sessionId = new String(message.getBody());
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        logger.info("[INTERRUPT_BROADCAST] 收到广播中断消息, sessionId={}", sessionId);
        interruptLocalSession(sessionId);
    }

    /**
     * 在当前节点执行完整的本地中断与清理。
     *
     * <p>包括：
     * <ol>
     *   <li>中断本地运行中的 Agent 并持久化 memory</li>
     *   <li>清理本地缓存的 ask_user suspended 状态（Caffeine，幂等）</li>
     *   <li>清理共享 Session 中的 pending tool 状态</li>
     *   <li>若本节点持有 SseEmitter（即发起 chat 请求的节点），通知前端"已停止生成"</li>
     * </ol>
     * 所有操作均为幂等，状态不存在时自动跳过。</p>
     *
     * @return true 表示本地命中并中断了运行中的 Agent
     */
    public boolean interruptLocalSession(String sessionId) {
        boolean interrupted = executionRegistry.interruptLocal(sessionId);
        agentSessionManager.remove(sessionId);
        pendingToolSessionStore.clear(sessionId);
        // 仅在本地确实中断了运行中的 Agent 时才通知前端。
        // 否则 Redis Pub/Sub 回环可能在新请求注册新 emitter 后误发 "已停止生成"。
        if (interrupted) {
            SseEmitter emitter = emitterRegistry.get(sessionId);
            if (emitter != null) {
                sseNotifier.sendInterrupted(emitter);
            }
        }
        return interrupted;
    }

    /**
     * 向所有节点广播中断指定 session 的 Agent。
     */
    public void broadcastInterrupt(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        redisTemplate.convertAndSend(INTERRUPT_CHANNEL, sessionId);
        logger.info("[INTERRUPT_BROADCAST] 已广播中断消息, sessionId={}", sessionId);
    }
}
