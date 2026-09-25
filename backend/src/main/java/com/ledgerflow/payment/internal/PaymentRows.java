package com.ledgerflow.payment.internal;

import com.ledgerflow.payment.Payment;
import com.ledgerflow.payment.PaymentState;
import com.ledgerflow.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public final class PaymentRows {
    public static final String COLUMNS = "id,customer_wallet_id,settlement_wallet_id,amount_minor,refunded_minor,currency,reference,metadata,status,failure_code,ledger_transaction_id,version,created_at,updated_at";
    private PaymentRows() { }
    public static Payment map(ResultSet rs, ObjectMapper json) throws SQLException {
        return new Payment(rs.getObject("id", UUID.class), rs.getObject("customer_wallet_id", UUID.class), rs.getObject("settlement_wallet_id", UUID.class),
                rs.getLong("amount_minor"), rs.getLong("refunded_minor"), Money.Currency.valueOf(rs.getString("currency")), rs.getString("reference"),
                json.readValue(rs.getString("metadata"), new TypeReference<Map<String, String>>() { }), PaymentState.valueOf(rs.getString("status")),
                rs.getString("failure_code"), rs.getObject("ledger_transaction_id", UUID.class), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
