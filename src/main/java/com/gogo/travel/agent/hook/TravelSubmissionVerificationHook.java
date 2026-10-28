package com.gogo.travel.agent.hook;

import com.alibaba.fastjson2.JSON;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.SessionReactorContext;
import com.gogo.travel.business.approval.repo.ApprovalRepository;
import com.gogo.travel.business.order.entity.TravelOrderStatus;
import com.gogo.travel.business.order.repo.TravelOrderRepository;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.HashSet;
import java.util.Arrays;
import java.util.regex.Pattern;

/** A conflict check is not a write receipt. Verify claimed submissions before showing success. */
@Component
public class TravelSubmissionVerificationHook implements Hook {
    private static final Logger logger = LoggerFactory.getLogger(TravelSubmissionVerificationHook.class);
    private static final Pattern CLAIM = Pattern.compile(
        "(?:已[^。！？\\n]{0,20}(?:提交|创建|发起)[^。！？\\n]{0,16}(?:差旅|申请|审批|出差)|"
        + "(?:差旅|出差|申请|审批)[^。！？\\n]{0,20}(?:已(?:成功)?(?:提交|创建|发起)|(?:提交|创建|发起)成功)|"
        + "(?i:successfully\\s+(?:submitted|created)\\s+(?:your\\s+)?(?:travel|trip)))");
    private static final Pattern ORDER_ID = Pattern.compile("(?<![A-Za-z0-9_])to_[A-Za-z0-9_-]+(?![A-Za-z0-9_])");
    private static final Pattern NEGATIVE = Pattern.compile("尚未|未曾|没有|暂无|未找到|未查询到|还未|未能|并未|未提交|未创建");
    private static final String SAFE_REPLY = "本次差旅申请尚未提交，系统没有收到已保存的差旅单和审批单号。"
        + "请先确认出发城市、目的地、出发和返回日期及出差事由，再提交审批。";
    private final TravelOrderRepository orders;
    private final ApprovalRepository approvals;

    public TravelSubmissionVerificationHook(TravelOrderRepository orders, ApprovalRepository approvals) {
        this.orders = orders; this.approvals = approvals;
    }
    @Override public int priority() { return 5; }

    @Override public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostActingEvent) && !(event instanceof PostReasoningEvent)) return Mono.just(event);
        return Mono.deferContextual(view -> {
            AgentSessionContext context = SessionReactorContext.from(view);
            if (context == null) return Mono.just(event);
            if (event instanceof PostActingEvent acting) {
                captureReceipt(acting, context);
            } else if (event instanceof PostReasoningEvent reasoning) {
                Msg response = reasoning.getReasoningMessage();
                if (response == null || response.hasContentBlocks(ToolUseBlock.class)
                        || !claimsSubmission(response.getTextContent())) return Mono.just(event);
                if (!hasVerifiedOrder(context, response.getTextContent())) {
                    logger.warn("[TravelSubmissionVerification] Blocked unverified success agent={}, runId={}",
                        event.getAgent().getName(), context.getRunId());
                    // Do not persist the rejected success claim into the next turn's memory.
                    reasoning.setReasoningMessage(Msg.builder().role(MsgRole.ASSISTANT)
                        .name(event.getAgent().getName()).content(TextBlock.builder().text(SAFE_REPLY).build()).build());
                    // Keep a bounded repair: ask the model once to perform/confirm the real action.
                    // A second unsupported claim becomes a deterministic, truthful response.
                    if (context.beginSubmissionRepair(event.getAgent().getAgentId())) {
                        reasoning.gotoReasoning(Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(
                            "提交结果校验失败：没有找到属于当前用户的差旅单和关联审批记录。"
                            + "冲突检查只是查询，绝不表示申请已经提交。不要沿用历史中没有单号的成功说法。"
                            + "如果用户尚未确认完整行程，请展示准确日期和事由并请求确认；"
                            + "只有用户已经确认后，才调用 submit_travel_approval。成功回复必须包含工具返回的真实差旅单号和审批单号。"
                            + "工具失败必须明确说明未提交，不得声称成功。").build()).build());
                    }
                }
            }
            return Mono.just(event);
        });
    }
    private void captureReceipt(PostActingEvent event, AgentSessionContext context) {
        if (event.getToolUse() == null || !"submit_travel_approval".equals(event.getToolUse().getName())
                || event.getToolResult() == null || event.getToolResult().getOutput() == null) return;
        for (ContentBlock block : event.getToolResult().getOutput()) {
            if (!(block instanceof TextBlock text)) continue;
            try {
                var result = JSON.parseObject(text.getText());
                String id = result.getString("orderId");
                if (result.getBooleanValue("success") && id != null && verified(context.getUserId(), id)) {
                    context.markTravelSubmissionVerified(id);
                }
            } catch (RuntimeException ignored) {
                // Malformed or failed tool responses are never evidence of a completed write.
            }
        }
    }
    private boolean hasVerifiedOrder(AgentSessionContext context, String text) {
        var candidates = new HashSet<String>();
        var matcher = ORDER_ID.matcher(text);
        while (matcher.find()) candidates.add(matcher.group());
        if (candidates.isEmpty()) candidates.addAll(context.getVerifiedTravelSubmissions());
        return !candidates.isEmpty() && candidates.stream().allMatch(id -> verified(context.getUserId(), id));
    }
    private boolean claimsSubmission(String text) {
        if (text == null || text.isBlank()) return false;
        return Arrays.stream(text.split("[。！？；，,\\n]"))
            .anyMatch(sentence -> CLAIM.matcher(sentence).find() && !NEGATIVE.matcher(sentence).find());
    }
    private boolean verified(String userId, String orderId) {
        try {
            return orders.findByOrderId(orderId).filter(order -> userId.equals(order.getUserId())
                    && order.getStatus() != null && order.getStatus() != TravelOrderStatus.DRAFT && order.getApprovalId() != null)
                .flatMap(order -> approvals.findByProcessInstanceId(order.getApprovalId())
                    .filter(approval -> userId.equals(approval.getUserId()) && orderId.equals(approval.getOrderId())))
                .isPresent();
        } catch (RuntimeException error) {
            logger.warn("[TravelSubmissionVerification] Could not verify receipt type={}", error.getClass().getSimpleName());
            return false;
        }
    }
}
