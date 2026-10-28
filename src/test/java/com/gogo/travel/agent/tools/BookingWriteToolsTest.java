package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.apikey.service.ApiKeyService;
import com.gogo.travel.business.booking.entity.BookingRecord;
import com.gogo.travel.business.booking.entity.BookingStatus;
import com.gogo.travel.business.booking.entity.BookingType;
import com.gogo.travel.business.booking.repo.BookingRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BookingWriteTools 单元测试：
 * parseCliResponse 的成功/失败分支解析，以及 cancel_booking 的归属校验、幂等、
 * 各业务类型取消分支（不实际执行 CLI，通过"未配置 API Key"路径覆盖平台失败分支）。
 *
 * @author Hollis
 */
@ExtendWith(MockitoExtension.class)
class BookingWriteToolsTest {

    private static final String USER_ID = "u_test";

    @Mock
    private BookingRecordRepository bookingRecordRepository;

    @Mock
    private ApiKeyService apiKeyService;

    private BookingWriteTools tools;
    private AgentSessionContext ctx;

    @BeforeEach
    void setUp() {
        tools = new BookingWriteTools(bookingRecordRepository, apiKeyService);
        ctx = new AgentSessionContext(USER_ID, "s_test");
    }

    private BookingRecord record(String bookingId, BookingType bizType, BookingStatus status, String externalOrderNo) {
        BookingRecord r = new BookingRecord();
        r.setBookingId(bookingId);
        r.setUserId(USER_ID);
        r.setBizType(bizType);
        r.setStatus(status);
        r.setExternalOrderNo(externalOrderNo);
        return r;
    }

    // ============ parseCliResponse ============

    @Test
    void parseCliResponse_flightSuccess() {
        BookingWriteTools.CliResult result = tools.parseCliResponse(
                "{\"successCode\": true, \"msg\": \"订单取消成功！\", \"errorCode\": 170000}");
        assertTrue(result.success());
    }

    @Test
    void parseCliResponse_trainSuccess() {
        BookingWriteTools.CliResult result = tools.parseCliResponse(
                "{\"successCode\": true, \"errorMessage\": null}");
        assertTrue(result.success());
    }

    @Test
    void parseCliResponse_failWithMsg() {
        BookingWriteTools.CliResult result = tools.parseCliResponse(
                "{\"successCode\": false, \"msg\": \"订单状态不允许取消\"}");
        assertFalse(result.success());
        assertEquals("订单状态不允许取消", result.errorMsg());
    }

    @Test
    void parseCliResponse_failWithErrorMessage() {
        BookingWriteTools.CliResult result = tools.parseCliResponse(
                "{\"successCode\": false, \"errorMessage\": \"车票已出票\"}");
        assertFalse(result.success());
        assertEquals("车票已出票", result.errorMsg());
    }

    @Test
    void parseCliResponse_failWithoutReason() {
        BookingWriteTools.CliResult result = tools.parseCliResponse("{\"successCode\": false}");
        assertFalse(result.success());
        assertEquals("平台返回取消失败（未提供具体原因）", result.errorMsg());
    }

    @Test
    void parseCliResponse_jsonWrappedInLogs_stillParsed() {
        BookingWriteTools.CliResult result = tools.parseCliResponse(
                "[INFO] calling...\n{\"successCode\": true}\ndone");
        assertTrue(result.success());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "no json here"})
    void parseCliResponse_invalidOutput_fails(String output) {
        assertFalse(tools.parseCliResponse(output).success());
    }

    // ============ cancel_booking：参数与归属校验 ============

    @Test
    void cancel_blankBookingId_rejected() {
        JSONObject o = JSON.parseObject(tools.cancelBooking(ctx, " ", null));
        assertFalse(o.getBooleanValue("success"));
        assertEquals("INVALID_PARAM", o.getString("errorCode"));
    }

    @Test
    void cancel_notFound_rejected() {
        when(bookingRecordRepository.findByBookingId("bk_404")).thenReturn(Optional.empty());
        JSONObject o = JSON.parseObject(tools.cancelBooking(ctx, "bk_404", null));
        assertFalse(o.getBooleanValue("success"));
        assertEquals("BOOKING_NOT_FOUND", o.getString("errorCode"));
    }

    @Test
    void cancel_wrongOwner_permissionDenied() {
        BookingRecord other = record("bk_1", BookingType.FLIGHT, BookingStatus.CONFIRMED, "TN001");
        other.setUserId("u_other");
        when(bookingRecordRepository.findByBookingId("bk_1")).thenReturn(Optional.of(other));

        JSONObject o = JSON.parseObject(tools.cancelBooking(ctx, "bk_1", null));

        assertFalse(o.getBooleanValue("success"));
        assertEquals("PERMISSION_DENIED", o.getString("errorCode"));
        verify(bookingRecordRepository, never()).save(any());
    }

    // ============ cancel_booking：幂等与业务分支 ============

    @Test
    void cancel_alreadyCancelled_idempotentSuccess() {
        BookingRecord cancelled = record("bk_1", BookingType.FLIGHT, BookingStatus.CANCELLED, "TN001");
        when(bookingRecordRepository.findByBookingId("bk_1")).thenReturn(Optional.of(cancelled));

        JSONObject o = JSON.parseObject(tools.cancelBooking(ctx, "bk_1", null));

        assertTrue(o.getBooleanValue("success"));
        verify(bookingRecordRepository, never()).save(any());
    }

    @Test
    void cancel_hotel_marksInternalOnlyAndKeepsReason() {
        BookingRecord hotel = record("bk_1", BookingType.HOTEL, BookingStatus.CONFIRMED, "TN002");
        when(bookingRecordRepository.findByBookingId("bk_1")).thenReturn(Optional.of(hotel));

        JSONObject o = JSON.parseObject(tools.cancelBooking(ctx, "bk_1", "行程有变"));

        assertTrue(o.getBooleanValue("success"));
        assertFalse(o.getBooleanValue("platformCancelled"));
        assertEquals(BookingStatus.CANCELLED, hotel.getStatus());
        assertEquals("行程有变", hotel.getRemark());
        verify(bookingRecordRepository).save(hotel);
    }

    @Test
    void cancel_flightWithoutExternalOrderNo_marksInternalOnly() {
        BookingRecord flight = record("bk_1", BookingType.FLIGHT, BookingStatus.CREATED, null);
        when(bookingRecordRepository.findByBookingId("bk_1")).thenReturn(Optional.of(flight));

        JSONObject o = JSON.parseObject(tools.cancelBooking(ctx, "bk_1", null));

        assertTrue(o.getBooleanValue("success"));
        assertFalse(o.getBooleanValue("platformCancelled"));
        assertEquals(BookingStatus.CANCELLED, flight.getStatus());
    }

    @Test
    void cancel_flightPlatformFail_internalStateUntouched() {
        // 未配置 API Key → 平台取消失败 → 内部状态不得变更、不得落库
        BookingRecord flight = record("bk_1", BookingType.FLIGHT, BookingStatus.CONFIRMED, "TN003");
        when(bookingRecordRepository.findByBookingId("bk_1")).thenReturn(Optional.of(flight));
        when(apiKeyService.getApiKey(USER_ID, "tuniu-cli")).thenReturn(null);

        JSONObject o = JSON.parseObject(tools.cancelBooking(ctx, "bk_1", null));

        assertFalse(o.getBooleanValue("success"));
        assertEquals("PLATFORM_CANCEL_FAILED", o.getString("errorCode"));
        assertEquals(BookingStatus.CONFIRMED, flight.getStatus());
        verify(bookingRecordRepository, never()).save(any());
    }
}
