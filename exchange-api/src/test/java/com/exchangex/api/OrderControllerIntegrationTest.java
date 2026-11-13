package com.exchangex.api;

import com.exchangex.engine.OrderCommandProcessor;
import com.exchangex.engine.Side;
import com.exchangex.engine.Trade;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private OrderCommandProcessor processor;

    private OrderRequest validBuyRequest;
    private OrderRequest validSellRequest;

    @BeforeEach
    void setUp() {
        validBuyRequest = new OrderRequest(1L, Side.BUY, 10000L, 10L);
        validSellRequest = new OrderRequest(2L, Side.SELL, 9900L, 5L);
        reset(processor);
    }

    @Test
    void submitValidBuyOrder_returns201() throws Exception {
        when(processor.submit(any()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBuyRequest)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(0)))
                .andExpect(result -> assertEquals("[]", result.getResponse().getContentAsString()));
    }

    @Test
    void submitValidSellOrder_returns201AndExecutesTrade() throws Exception {
        Trade trade = new Trade(1L, 2L, 9900L, 5L);
        when(processor.submit(any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(trade)));

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validSellRequest)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].buyOrderId", is(1)))
                .andExpect(jsonPath("$[0].sellOrderId", is(2)))
                .andExpect(jsonPath("$[0].price", is(9900)))
                .andExpect(jsonPath("$[0].quantity", is(5)));
    }

    @Test
    void submitPartialFill_returnsExecutedTrades() throws Exception {
        Trade trade = new Trade(1L, 2L, 10000L, 5L);
        when(processor.submit(any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(trade)));

        OrderRequest largeBuy = new OrderRequest(1L, Side.BUY, 10000L, 10L);
        MvcResult mvcResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(largeBuy)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].quantity", is(5)));
    }

    @Test
    void getActiveOrder_returns200WithOrderView() throws Exception {
        var orderView = new com.exchangex.engine.OrderView(1L, Side.BUY, 10000L, 10L);
        when(processor.find(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(java.util.Optional.of(orderView)));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/orders/1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.side", is("BUY")))
                .andExpect(jsonPath("$.price", is(10000)))
                .andExpect(jsonPath("$.remainingQuantity", is(10)));
    }

    @Test
    void getMissingOrder_returns404() throws Exception {
        when(processor.find(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(java.util.Optional.empty()));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/orders/999"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Active order not found")));
    }

    @Test
    void cancelActiveOrder_returns204() throws Exception {
        when(processor.cancel(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(true));

        MvcResult mvcResult = mockMvc.perform(delete("/api/v1/orders/1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isNoContent());
    }

    @Test
    void cancelNonexistentOrder_returns404() throws Exception {
        when(processor.cancel(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(false));

        MvcResult mvcResult = mockMvc.perform(delete("/api/v1/orders/999"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Active order not found")));
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
    void submitDuplicateOrderId_returns400() throws Exception {
        when(processor.submit(any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalArgumentException("duplicate order ID")));

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBuyRequest)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Invalid request")))
                .andExpect(jsonPath("$.detail", containsString("duplicate order ID")));
    }

    @Test
    void submitUnknownInstrument_returns400() throws Exception {
        when(processor.submit(any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalArgumentException("wrong instrument")));

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBuyRequest)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Invalid request")))
                .andExpect(jsonPath("$.detail", containsString("wrong instrument")));
    }

    @Test
    void getOrderBook_returns200WithBestBidAndAsk() throws Exception {
        var bestBid = java.util.Optional.of(new com.exchangex.engine.OrderView(1L, Side.BUY, 10000L, 5L));
        var bestAsk = java.util.Optional.of(new com.exchangex.engine.OrderView(2L, Side.SELL, 10100L, 3L));
        var snapshot = new OrderCommandProcessor.BookSnapshot(bestBid, bestAsk);

        when(processor.snapshot())
                .thenReturn(CompletableFuture.completedFuture(snapshot));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/orderbook/AAPL"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.symbol", is("AAPL")))
                .andExpect(jsonPath("$.bestBid.id", is(1)))
                .andExpect(jsonPath("$.bestBid.price", is(10000)))
                .andExpect(jsonPath("$.bestAsk.id", is(2)))
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
        when(processor.trades())
                .thenReturn(CompletableFuture.completedFuture(List.of(trade1, trade2)));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/trades"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].buyOrderId", is(1)))
                .andExpect(jsonPath("$[0].sellOrderId", is(2)))
                .andExpect(jsonPath("$[1].buyOrderId", is(3)))
                .andExpect(jsonPath("$[1].sellOrderId", is(4)));
    }

    @Test
    void submitWhenQueueSaturated_returns503() throws Exception {
        when(processor.submit(any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new java.util.concurrent.RejectedExecutionException("Queue full")));

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBuyRequest)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error", is("Command queue busy or shutting down")));
    }

    @Test
    void cancelWhenQueueSaturated_returns503() throws Exception {
        when(processor.cancel(anyLong()))
                .thenReturn(CompletableFuture.failedFuture(
                        new java.util.concurrent.RejectedExecutionException("Queue full")));

        MvcResult mvcResult = mockMvc.perform(delete("/api/v1/orders/1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error", is("Command queue busy or shutting down")));
    }

    @Test
    void getOrderWhenQueueSaturated_returns503() throws Exception {
        when(processor.find(anyLong()))
                .thenReturn(CompletableFuture.failedFuture(
                        new java.util.concurrent.RejectedExecutionException("Queue full")));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/orders/1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error", is("Command queue busy or shutting down")));
    }

    @Test
    void getOrderBookWhenQueueSaturated_returns503() throws Exception {
        when(processor.snapshot())
                .thenReturn(CompletableFuture.failedFuture(
                        new java.util.concurrent.RejectedExecutionException("Queue full")));

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
        when(processor.trades())
                .thenReturn(CompletableFuture.failedFuture(
                        new java.util.concurrent.RejectedExecutionException("Queue full")));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/trades"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error", is("Command queue busy or shutting down")));
    }
}