package com.clixsell.pos.sale;

import com.clixsell.pos.common.LocalDateTimeTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * How a sale was paid (report: Payment class, PAYMENTS table). The payment
 * method is recorded only; no card or wallet is actually charged.
 */
@Entity
@Table(name = "payments")
public class Payment {

    /** Only PAID exists: a sale is saved only once its payment is recorded. */
    public enum PaymentStatus { PAID }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long paymentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod method;

    @Column(nullable = false)
    private double amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus status;

    @Column(name = "paid_at", nullable = false)
    @Convert(converter = LocalDateTimeTextConverter.class)
    private LocalDateTime paidAt;

    protected Payment() {
    }

    Payment(Sale sale, PaymentMethod method, double amount, LocalDateTime paidAt) {
        this.sale = sale;
        this.method = method;
        this.amount = amount;
        this.status = PaymentStatus.PAID;
        this.paidAt = paidAt;
    }

    public Long getPaymentId() { return paymentId; }
    public PaymentMethod getMethod() { return method; }
    public double getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
    public LocalDateTime getPaidAt() { return paidAt; }
}
