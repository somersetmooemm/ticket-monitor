package app;

/**
 * Одно предложение (лот) или группа мест в одном ряду.
 */
public record Lot(Event event, String lotId, String category, int qty, String place,
                  double price, String currency, Integer maxRun, String runs, String url) {
}
