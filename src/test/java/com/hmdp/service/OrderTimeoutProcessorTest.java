package com.hmdp.service;

import com.hmdp.entity.VoucherOrder;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderTimeoutProcessorTest {

    private final IVoucherOrderService orderService = mock(IVoucherOrderService.class);
    private final SeckillReservationService reservationService = mock(SeckillReservationService.class);
    private final OrderTimeoutProcessor processor =
            new OrderTimeoutProcessor(orderService, reservationService);

    @Test
    void paidOrderShouldNotRestoreRedisStock() {
        when(orderService.closeUnpaidOrder(100L)).thenReturn(false);

        processor.process(100L);

        verify(reservationService, never()).cancelCreatedOrder(
                org.mockito.ArgumentMatchers.any(SeckillReservation.class));
    }

    @Test
    void cancelledOrderShouldRestoreRedisStockIdempotently() {
        VoucherOrder order = new VoucherOrder().setId(100L).setUserId(7L).setVoucherId(9L).setStatus(4);
        SeckillReservation reservation = new SeckillReservation();
        reservation.setOrderId(100L);
        reservation.setUserId(7L);
        reservation.setVoucherId(9L);
        reservation.setStatus("CREATED");

        when(orderService.closeUnpaidOrder(100L)).thenReturn(true);
        when(orderService.getById(100L)).thenReturn(order);
        when(reservationService.find(100L)).thenReturn(reservation);

        processor.process(100L);

        verify(reservationService).cancelCreatedOrder(reservation);
    }
}
