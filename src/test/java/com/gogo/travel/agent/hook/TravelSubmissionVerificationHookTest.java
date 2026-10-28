package com.gogo.travel.agent.hook;

import com.gogo.travel.agent.context.*;
import com.gogo.travel.business.order.entity.*;
import com.gogo.travel.business.order.repo.TravelOrderRepository;
import com.gogo.travel.business.approval.entity.*;
import com.gogo.travel.business.approval.repo.ApprovalRepository;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.*;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TravelSubmissionVerificationHookTest {
    final TravelOrderRepository orders = mock(TravelOrderRepository.class);
    final ApprovalRepository approvals = mock(ApprovalRepository.class);
    final TravelSubmissionVerificationHook hook = new TravelSubmissionVerificationHook(orders, approvals);
    final AgentSessionContext context = new AgentSessionContext("alice", "session");
    final Agent agent = mock(Agent.class);
    TravelSubmissionVerificationHookTest() { when(agent.getAgentId()).thenReturn("manage-1"); when(agent.getName()).thenReturn("ItineraryManageAgent"); }
    Msg text(String value) { return Msg.builder().role(MsgRole.ASSISTANT).content(TextBlock.builder().text(value).build()).build(); }
    PostReasoningEvent reason(String text) { return new PostReasoningEvent(agent,"qwen",null,text(text)); }
    <T extends HookEvent> void fire(T event) { hook.onEvent(event).contextWrite(SessionReactorContext.of(context)).block(); }
    void saved(String owner) {
        var order = TravelOrder.builder().orderId("to_real").userId(owner).status(TravelOrderStatus.SUBMITTED).approvalId("ap_real").build();
        var approval = new ApprovalRecord("ap_real",owner,"travel",ApprovalRecordStatus.PENDING,"{}",null,Instant.now(),Instant.now());
        approval.setOrderId("to_real");
        when(orders.findByOrderId("to_real")).thenReturn(Optional.of(order));
        when(approvals.findByProcessInstanceId("ap_real")).thenReturn(Optional.of(approval));
    }
    @Test void conflictCheckAloneNeverAllowsAReportedSubmissionAndRepairIsBounded() {
        var tool = ToolUseBlock.builder().id("c").name("check_travel_order_conflicts").input(Map.of()).build();
        fire(new PostActingEvent(agent,new Toolkit(),tool,new ToolResultBlock("c",tool.getName(),List.of(TextBlock.builder().text("{\"has_conflict\":false}").build()))));
        var first=reason("已为您提交差旅申请，差旅审批已提交，系统正在处理中。"); fire(first);
        assertThat(first.isGotoReasoningRequested()).isTrue();
        assertThat(first.getReasoningMessage().getTextContent()).contains("尚未提交").doesNotContain("已为您提交");
        var second=reason("差旅审批已提交成功。"); fire(second);
        assertThat(second.isGotoReasoningRequested()).isFalse();
        assertThat(second.getReasoningMessage().getTextContent()).contains("尚未提交");
        verifyNoInteractions(orders,approvals);
    }
    @Test void actualSubmissionReceiptAllowsSuccessForParentAndChild() {
        saved("alice");
        var tool=ToolUseBlock.builder().id("s").name("submit_travel_approval").input(Map.of()).build();
        fire(new PostActingEvent(agent,new Toolkit(),tool,new ToolResultBlock("s",tool.getName(),List.of(TextBlock.builder()
            .text("{\"success\":true,\"orderId\":\"to_real\",\"processInstanceId\":\"ap_real\"}").build()))));
        var event=reason("已为您提交差旅申请。差旅单号 to_real，审批单号 ap_real。"); fire(event);
        assertThat(event.isGotoReasoningRequested()).isFalse();
        assertThat(context.getVerifiedTravelSubmissions()).containsExactly("to_real");
    }
    @Test void inventedIdsAndOtherUsersOrdersDoNotCountAsEvidence() {
        saved("bob");
        var event=reason("差旅申请已提交，差旅单号 to_real。"); fire(event);
        assertThat(event.isGotoReasoningRequested()).isTrue();
        verifyNoInteractions(approvals);
    }
    @Test void failedWriteAndUnlinkedApprovalNeverCountAsSuccess() {
        var order=TravelOrder.builder().orderId("to_real").userId("alice").status(TravelOrderStatus.SUBMITTED).approvalId("missing").build();
        when(orders.findByOrderId("to_real")).thenReturn(Optional.of(order));
        var event=reason("差旅申请已提交，单号 to_real。"); fire(event);
        assertThat(event.isGotoReasoningRequested()).isTrue();
    }
    @Test void pendingConfirmationAndNegativeQueryResultsRemainUnchanged() {
        for (String message : List.of("是否确认提交差旅审批？", "本次差旅申请尚未提交。", "暂无已提交的差旅申请。")) {
            var event=reason(message); fire(event);
            assertThat(event.isGotoReasoningRequested()).isFalse();
            assertThat(event.getReasoningMessage().getTextContent()).isEqualTo(message);
        }
        verifyNoInteractions(orders,approvals);
    }

    @Test void successVariantsAndUnrelatedNegationCannotHideAnUnsupportedSubmission() {
        for (String message : List.of("差旅申请已成功提交。", "差旅审批已发起。", "差旅申请提交成功。",
                "没有发现行程冲突，已为您提交差旅申请。", "差旅申请已提交，没有冲突。")) {
            var event = reason(message);
            fire(event);
            assertThat(event.getReasoningMessage().getTextContent()).contains("尚未提交");
        }
        verifyNoInteractions(orders, approvals);
    }

    @Test void databaseReadFailureMustNotAllowASuccessClaim() {
        when(orders.findByOrderId("to_real")).thenThrow(new IllegalStateException("database unavailable"));
        var event = reason("差旅申请已提交，差旅单号 to_real。");
        fire(event);
        assertThat(event.isGotoReasoningRequested()).isTrue();
        assertThat(event.getReasoningMessage().getTextContent()).contains("尚未提交");
    }
}
