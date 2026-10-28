package app;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class SheetsWriter {
    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger(SheetsWriter.class);
    static final String ACTUAL = "Актуальные";

    private static final List<Object> HEADER = List.of(
            "Статус", "Концерт", "Город", "Дата", "Площадка", "Категория", "Кол-во", "Место",
            "Цена за 1 шт.", "Валюта", "Макс. мест подряд", "Можно взять рядом (шт.)",
            "Ссылка", "Обновлено", "ID лота", "ID события");

    private final Sheets sheets;
    private final String id;
    private final ZoneId zone;
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public SheetsWriter() throws Exception {
        String json = System.getenv("GOOGLE_CREDENTIALS_JSON");
        id = System.getenv("SPREADSHEET_ID");
        if (json == null || json.isBlank() || id == null || id.isBlank()) {
            throw new IllegalStateException("Не заданы GOOGLE_CREDENTIALS_JSON и SPREADSHEET_ID");
        }
        zone = ZoneId.of(System.getenv().getOrDefault("TIMEZONE", "Europe/Moscow"));
        GoogleCredentials creds = GoogleCredentials
                .fromStream(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
                .createScoped(List.of(SheetsScopes.SPREADSHEETS));
        sheets = new Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(), new HttpCredentialsAdapter(creds))
                .setApplicationName("ticket-monitor").build();
    }

    /** Полная замена содержимого: старые строки и статусы не используются. */
    public void write(List<Lot> lots, Set<String> failedEvents, List<String[]> errors) throws Exception {
        String now = ZonedDateTime.now(zone).format(fmt);
        List<List<Object>> rows = prepareRows(lots, now);
        LOG.info("sheets.replace.start collected={} failedEvents={} rows={}", lots.size(), failedEvents.size(), rows.size());
        // По требованию: сначала очищаем ВСЮ вкладку, затем записываем текущую выгрузку.
        // Это две операции API: если update упадёт после clear, лист останется пустым.
        sheets.spreadsheets().values().clear(id, q(ACTUAL),
                new com.google.api.services.sheets.v4.model.ClearValuesRequest()).execute();
        LOG.info("sheets.clear.complete sheet={}", ACTUAL);
        sheets.spreadsheets().values().update(id, q(ACTUAL) + "!A1:P" + rows.size(),
                        new ValueRange().setValues(rows))
                .setValueInputOption("RAW").execute();
        LOG.info("sheets.replace.complete currentLots={} failedEvents={}", lots.size(), failedEvents.size());
    }

    static List<List<Object>> prepareRows(List<Lot> lots, String now) {
        List<List<Object>> rows = new ArrayList<>();
        for (Lot l : lots) {
            Event e = l.event();
            rows.add(new ArrayList<>(Arrays.asList("", e.name(), e.city(), e.date(), e.platform(), l.category(),
                    l.qty(), l.place(), l.price(), l.currency(),
                    l.maxRun() == null ? "" : l.maxRun(), l.runs() == null ? "" : l.runs(),
                    l.url(), now, l.lotId(), e.url())));
        }
        rows.sort(Comparator.comparing((List<Object> r) -> String.valueOf(r.get(1)))
                .thenComparing(r -> String.valueOf(r.get(3)))
                .thenComparing(r -> String.valueOf(r.get(5)))
                .thenComparingDouble(r -> ((Number) r.get(8)).doubleValue()));
        List<List<Object>> out = new ArrayList<>();
        out.add(HEADER);
        out.addAll(rows);
        return out;
    }

    private static String q(String sheet) {
        return "'" + sheet + "'";
    }

}
