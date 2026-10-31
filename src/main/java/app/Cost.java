package app;

/**
 * Итоговая стоимость ОДНОГО билета в рублях: цена по курсу ЦБ + 32 000 ₽ комиссии.
 * На количество и прочие поправки (разница с курсом банка ~2 000 ₽) умножается/добавляется вручную.
 */
final class Cost {
    static final double FEE_RUB = 32_000;

    private Cost() {
    }

    /** Сумма за 1 билет в рублях или "" (пустая ячейка), если курса валюты нет. */
    static Object totalRub(Lot l, Rates rates) {
        Double rate = rates.rub(l.currency());
        if (rate == null) return "";
        return (long) (Math.ceil((l.price() * rate + FEE_RUB) / 1_000.0) * 1_000);
    }

}
