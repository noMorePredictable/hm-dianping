package com.hmdp.mq;

import com.hmdp.config.SeckillProperties;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.SeckillReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillMessagePublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final SeckillReservationService reservationService = mock(SeckillReservationService.class);
    private final SeckillMessagePublisher publisher = new SeckillMessagePublisher(
            rabbitTemplate, reservationService, new SeckillProperties());

    @Test
    void finalReservationShouldNotBePublishedAgain() {
        VoucherOrder order = new VoucherOrder().setId(1L).setUserId(2L).setVoucherId(3L);
        when(reservationService.markPublishing(1L)).thenReturn(-1L);

        assertThat(publisher.publishCreateOrder(order)).isFalse();

        verify(rabbitTemplate, never()).convertAndSend(
                anyString(), anyString(), any(),
                any(org.springframework.amqp.core.MessagePostProcessor.class),
                any(CorrelationData.class));
    }

    @Test
    void ackShouldMoveReservationToPublished() {
        publisher.handleConfirm(new CorrelationData("CREATE_ORDER:88"), true, null);

        verify(reservationService).markPublished(88L);
        verify(reservationService, never()).markPublishFailed(
                org.mockito.ArgumentMatchers.eq(88L), anyString());
    }

    @Test
    void nackShouldScheduleRecovery() {
        publisher.handleConfirm(new CorrelationData("CREATE_ORDER:88"), false, "disk error");

        verify(reservationService).markPublishFailed(88L, "NACK:disk error");
    }
}
