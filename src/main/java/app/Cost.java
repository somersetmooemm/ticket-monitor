package app;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class Cost {

    private static final BigDecimal FEE_RUB = new BigDecimal("32000");
    private static final BigDecimal RATE_MARKUP = new BigDecimal("1.08");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private Cost() {
    }

    /**
     * Стоимость одного билета:
     * курс ЦБ + 10%, комиссия 32000 RUB,
     * округление вверх до 1000 RUB.
     */
    static long totalRub(Lot lot, Rates rates) {
        Double rate = rates.rub(lot.currency());
        if (rate == null) {
            throw new IllegalStateException(
                    "Не найден курс валюты: " + lot.currency()
            );
        }
        BigDecimal exchangeRate = BigDecimal.valueOf(rate);
        if (!"RUB".equalsIgnoreCase(lot.currency().strip())) {
            exchangeRate = exchangeRate.multiply(RATE_MARKUP);
        }

        BigDecimal price = BigDecimal.valueOf(lot.price());
        BigDecimal total = price.multiply(exchangeRate).add(FEE_RUB);

        // Округление вверх до ближайшей тысячи
        BigDecimal rounded = total.divide(THOUSAND, 0, RoundingMode.CEILING).multiply(THOUSAND);
        return rounded.longValueExact();
    }
}