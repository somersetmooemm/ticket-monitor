
package app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Cost — расчёт стоимости одного билета")
class CostTest {

    private static final double USD_RATE = 80.0;
    private static final double EUR_RATE = 100.0;

    private final Rates rates = new Rates(Map.of(
            "USD", USD_RATE,
            "EUR", EUR_RATE
    ));

    private Lot lot(double price, String currency) {
        return new Lot(
                null,       // event
                "LOT-001",  // lotId
                "VIP",      // category
                1,          // qty
                "A1",       // place
                price,      // price
                currency,   // currency
                null,       // maxRun
                null,       // runs
                null        // url
        );
    }

    @Test
    @DisplayName("К курсу USD добавляется 10%")
    void shouldApplyTenPercentMarkupToUsd() {

        // 500 * (80 * 1.10) + 32000 = 76000
        Lot lot = lot(500, "USD");

        assertEquals(
                76_000L,
                Cost.totalRub(lot, rates)
        );
    }

    @Test
    @DisplayName("К курсу EUR добавляется 10%")
    void shouldApplyTenPercentMarkupToEur() {

        // 100 * (100 * 1.10) + 32000 = 43000
        Lot lot = lot(100, "EUR");

        assertEquals(
                43_000L,
                Cost.totalRub(lot, rates)
        );
    }

    @Test
    @DisplayName("Для RUB наценка 10% не применяется")
    void shouldNotApplyMarkupToRub() {

        // 10000 * 1 + 32000 = 42000
        Lot lot = lot(10_000, "RUB");

        assertEquals(
                42_000L,
                Cost.totalRub(lot, rates)
        );
    }

    @Test
    @DisplayName("Комиссия 32000 рублей добавляется один раз")
    void shouldAddFixedFeeOnce() {

        // 100 * 88 + 32000 = 40800 -> 41000
        Lot lot = lot(100, "USD");

        assertEquals(
                41_000L,
                Cost.totalRub(lot, rates)
        );
    }

    @Test
    @DisplayName("Результат округляется вверх до тысячи")
    void shouldRoundUpToNextThousand() {

        // 101 * 88 + 32000 = 40888 -> 41000
        Lot lot = lot(101, "USD");

        assertEquals(
                41_000L,
                Cost.totalRub(lot, rates)
        );
    }

    @Test
    @DisplayName("Кратная тысяче сумма не увеличивается")
    void shouldNotRoundAlreadyRoundedAmount() {

        // 500 * 88 + 32000 = 76000
        Lot lot = lot(500, "USD");

        assertEquals(
                76_000L,
                Cost.totalRub(lot, rates)
        );
    }

    @Test
    @DisplayName("Даже одна копейка округляет вверх")
    void shouldRoundUpFractionalAmount() {

        // 10000.01 RUB + 32000 = 42000.01 -> 43000
        Lot lot = lot(10_000.01, "RUB");

        assertEquals(
                43_000L,
                Cost.totalRub(lot, rates)
        );
    }

    @Test
    @DisplayName("Нулевая цена возвращает только комиссию")
    void shouldHandleZeroPrice() {

        Lot lot = lot(0, "USD");

        assertEquals(
                32_000L,
                Cost.totalRub(lot, rates)
        );
    }

    @Test
    @DisplayName("Неизвестная валюта вызывает исключение")
    void shouldThrowWhenCurrencyUnknown() {

        Lot lot = lot(500, "THB");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> Cost.totalRub(lot, rates)
        );

        assertTrue(
                exception.getMessage().contains("THB")
        );
    }

    @Test
    @DisplayName("Пустая карта курсов вызывает исключение для USD")
    void shouldThrowWhenRatesEmpty() {

        Lot lot = lot(500, "USD");

        assertThrows(
                IllegalStateException.class,
                () -> Cost.totalRub(lot, Rates.none())
        );
    }

    @Test
    @DisplayName("Регистр валюты не влияет на результат")
    void shouldIgnoreCurrencyCase() {

        assertAll(
                () -> assertEquals(
                        76_000L,
                        Cost.totalRub(lot(500, "USD"), rates)
                ),
                () -> assertEquals(
                        76_000L,
                        Cost.totalRub(lot(500, "usd"), rates)
                )
        );
    }

    @Test
    @DisplayName("Пробелы в коде валюты не влияют на результат")
    void shouldTrimCurrency() {

        Lot lot = lot(500, " USD ");

        assertEquals(
                76_000L,
                Cost.totalRub(lot, rates)
        );
    }
}
