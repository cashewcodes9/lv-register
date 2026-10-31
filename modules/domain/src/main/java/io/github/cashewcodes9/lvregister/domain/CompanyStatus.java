package io.github.cashewcodes9.lvregister.domain;

/**
 * Status combined from `register.closed`, open liquidations and open insolvency proceedings
 * (source assessment 4.1).
 */
public enum CompanyStatus {
    ACTIVE,
    IN_LIQUIDATION,
    INSOLVENT,
    LEGAL_PROTECTION,
    TERMINATED,
    REORGANIZED;

    public boolean isClosed() {
        return this == TERMINATED || this == REORGANIZED;
    }
}
