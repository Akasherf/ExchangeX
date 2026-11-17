package com.exchangex.api;

import com.exchangex.engine.Side;
import com.exchangex.engine.Trade;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestConfig.class)
class OrderControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private DurableCommandCoordinator coordinator;

    private OrderRequest validBuyRequest;
    private OrderRequest validSellRequest;

    @BeforeEach
    void setUp() {
        validBuyRequest = new OrderRequest(1L, Side.BUY, 10000L, 10L);
        validSellRequest = new OrderRequest(2L, Side.SELL, 9900L, 5L);
        reset(coordinator);
    }

    @Test
    void submitValidBuyOrder_returns202Accepted() throws Exception {
        when(coordinator.submitOrder(anyLong(), any(), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(
                        new DurableCommandCoordinator.AdmissionResult(1L, false)
                ));

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBuyRequest)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.sequenceId", is(1)));
    }

    @Test
    void submitValidSellOrder_returns202Accepted() throws Exception {
        when(coordinator.submitOrder(anyLong(), any(), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(
                        new DurableCommandCoordinator.AdmissionResult(2L, false)
                ));

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validSellRequest)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.sequenceId", is(2)));
    }

    @Test
    void submitDuplicateOrderIdDifferentPayload_returns409Conflict() throws Exception {
        when(coordinator.submitOrder(anyLong(), any(), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalArgumentException("Duplicate order ID with different payload: 1")
                ));

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBuyRequest)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is("CONFLICT")));
    }

    @Test
    void getActiveOrder_returns200WithOrderView() throws Exception {
        var orderView = new com.exchangex.engine.OrderView(1L, Side.BUY, 10000L, 10L);
        when(coordinator.findOrder(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(orderView)));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/orders/1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.side", is("BUY")))
                .andExpect(jsonPath("$.price", is(10000)))
                .andExpect(jsonPath("$.remainingQuantity", is(10)))
                .andExpect(jsonPath("$.executionStatus", is("PENDING")));
    }

    @Test
    void getMissingOrder_returns404() throws Exception {
        when(coordinator.findOrder(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/orders/999"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.executionStatus", is("NOT_FOUND")));
    }

    @Test
    void cancelActiveOrder_returns202Accepted() throws Exception {
        when(coordinator.cancelOrder(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(
                        new DurableCommandCoordinator.AdmissionResult(1L, false)
                ));

        MvcResult mvcResult = mockMvc.perform(delete("/api/v1/orders/1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("PENDING")));
    }

    @Test
    void cancelNonexistentOrder_returns404() throws Exception {
        when(coordinator.cancelOrder(anyLong()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalArgumentException("Order not found: 999")
                ));

        MvcResult mvcResult = mockMvc.perform(delete("/api/v1/orders/999"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", containsString("NOT_FOUND")));
    }

    @Test
    void submitInvalidOrderData_returns400() throws Exception {
        String invalidJson = """
                {"id": -1, "side": "BUY", "price": 10000, "quantity": 10}
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Invalid request")))
                .andExpect(jsonPath("$.detail", not(emptyString())));
    }

    @Test
    void submitMissingRequiredFields_returns400() throws Exception {
        String invalidJson = """
                {"id": 1}
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Invalid request")));
    }

    @Test
    void getOrderBook_returns200WithBestBidAndAsk() throws Exception {
        var bestBid = Optional.of(new com.exchangex.engine.OrderView(1L, Side.BUY, 10000L, 5L));
        var bestAsk = Optional.of(new com.exchangex.engine.OrderView(2L, Side.SELL, 10100L, 3L));
        var snapshot = new DurableCommandCoordinator.BookSnapshot(bestBid, bestAsk);

        when(coordinator.snapshot())
                .thenReturn(CompletableFuture.completedFuture(snapshot));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/orderbook/AAPL"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.symbol", is("AAPL")))
                .andExpect(jsonPath("$.bestBid.id").doesNotExist())
                .andExpect(jsonPath("$.bestBid.price", is(10000)))
                .andExpect(jsonPath("$.bestAsk.id").doesNotExist())
                .andExpect(jsonPath("$.bestAsk.price", is(10100)));
    }

    @Test
    void getOrderBookUnknownInstrument_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/orderbook/UNKNOWN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Unknown instrument")));
    }

    @Test
    void getTradeHistory_returns200WithTrades() throws Exception {
        Trade trade1 = new Trade(1L, 2L, 10000L, 5L);
        Trade trade2 = new Trade(3L, 4L, 9900L, 3L);
        when(coordinator.trades())
                .thenReturn(CompletableFuture.completedFuture(List.of(trade1, trade2)));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/trades"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].price", is(10000)))
                .andExpect(jsonPath("$[0].buyOrderId").doesNotExist())
                .andExpect(jsonPath("$[0].sellOrderId").doesNotExist())
                .andExpect(jsonPath("$[1].price", is(9900)))
                .andExpect(jsonPath("$[1].buyOrderId").doesNotExist());
    }

    @Test
    void submitWhenQueueSaturated_returns503() throws Exception {
        when(coordinator.submitOrder(anyLong(), any(), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.failedFuture(
                        new java.util.concurrent.RejectedExecutionException("Queue full")
                ));

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBuyRequest)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.status", is("QUEUE_FULL")));
    }

    @Test
    void cancelWhenQueueSaturated_returns503() throws Exception {
        when(coordinator.cancelOrder(anyLong()))
                .thenReturn(CompletableFuture.failedFuture(
                        new java.util.concurrent.RejectedExecutionException("Queue full")
                ));

        MvcResult mvcResult = mockMvc.perform(delete("/api/v1/orders/1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.status", is("QUEUE_FULL")));
    }


    @Test
    void getOrderWhenQueueSaturated_returns503() throws Exception {
        when(coordinator.findOrder(anyLong()))
                .thenReturn(CompletableFuture.failedFuture(
                        new java.util.concurrent.RejectedExecutionException("Queue full")
                ));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/orders/1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error",
                        is("Command queue busy or shutting down")));
    }


    @Test
    void getOrderBookWhenQueueSaturated_returns503() throws Exception {
        when(coordinator.snapshot())
                .thenReturn(CompletableFuture.failedFuture(
                        new java.util.concurrent.RejectedExecutionException("Queue full")
                ));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/orderbook/AAPL"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error", is("Command queue busy or shutting down")));
    }

    @Test
    void getTradesWhenQueueSaturated_returns503() throws Exception {
        when(coordinator.trades())
                .thenReturn(CompletableFuture.failedFuture(
                        new java.util.concurrent.RejectedExecutionException("Queue full")
                ));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/trades"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error", is("Command queue busy or shutting down")));
    }
}