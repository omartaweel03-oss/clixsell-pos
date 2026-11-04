package com.clixsell.pos.sale;

import com.clixsell.pos.common.LocalDateTimeTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * Digital receipt of a completed sale (report: Receipt class, RECEIPTS table,
 * SR12). Its QR code holds a unique, unguessable code; scanning or typing it
 * finds the sale again, e.g. for a return.
 */
@Entity
@Table(name = "receipts")
public class Receipt {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "receipt_id")
    private Long receiptId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false, unique = true)
    private Sale sale;

    @Column(name = "qr_code_value", nullable = false, unique = true)
    private String qrCodeValue;

    @Column(name = "receipt_url")
    private String receiptUrl;

    @Column(name = "issued_at", nullable = false)
    @Convert(converter = LocalDateTimeTextConverter.class)
    private LocalDateTime issuedAt;

    protected Receipt() {
    }

    static Receipt issue(Sale sale, LocalDateTime issuedAt) {
        Receipt receipt = new Receipt();
        receipt.sale = sale;
        receipt.issuedAt = issuedAt;
        receipt.qrCodeValue = generateQRCode();
        return receipt;
    }

    /** A unique code for the QR image, e.g. "CLIXSELL-7F3A9C01D2E4B6A8". */
    static String generateQRCode() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "CLIXSELL-" + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    public Long getReceiptId() { return receiptId; }
    public String getQrCodeValue() { return qrCodeValue; }
    public String getReceiptUrl() { return receiptUrl; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
}
