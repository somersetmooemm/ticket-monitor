package app;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class SheetsWriter {

    static final String ACTUAL = "Актуальные";
    static final String HISTORY = "История";
    static final String LOG = "Лог";

    private static final List<Object> HEADER = List.of(
            "Статус", "Концерт", "Город", "Дата", "Площадка", "Категория", "Кол-во", "Место",
            "Цена за 1 шт.", "Валюта", "Макс. мест подряд", "Блоков подряд (1 | 2 | 3 | 4+)",
            "Ссылка", "Обновлено", "ID лота", "ID события");
    private static final int C_PRICE = 8, C_LOT = 14, C_EVENT = 15;

    private final Sheets sheets;
    private final String id;
    private final ZoneId zone;
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public SheetsWriter() throws Exception {
        String json = System.getenv("GOOGLE_CREDENTIALS_JSON");
        id = System.getenv("SPREADSHEET_ID");
        if (json == null || id == null) {
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

    public void ensureSheets() throws Exception {
        Spreadsheet ss = sheets.spreadsheets().get(id).execute();
        Set<String> have = ss.getSheets().stream()
                .map(s -> s.getProperties().getTitle()).collect(Collectors.toSet());
        List<Request> reqs = new ArrayList<>();
        for (String t : List.of(ACTUAL, HISTORY, LOG)) {
            if (!have.contains(t)) {
                reqs.add(new Request().setAddSheet(
                        new AddSheetRequest().setProperties(new SheetProperties().setTitle(t))));
            }
        }
        if (!reqs.isEmpty()) {
            sheets.spreadsheets().batchUpdate(id,
                    new BatchUpdateSpreadsheetRequest().setRequests(reqs)).execute();
        }
    }

    /**
     * failedEvents - URL событий, которые не удалось обновить: их старые строки сохраняются.
     */
    public void write(List<Lot> lots, Set<String> failedEvents, List<String[]> errors) throws Exception {
        String now = ZonedDateTime.now(zone).format(fmt);

        List<List<Object>> old = readOld();
        Map<String, Double> oldPrice = new HashMap<>();
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> r0 : old) {
            List<Object> r = new ArrayList<>(r0);
            while (r.size() < HEADER.size()) r.add("");
            Double p = num(r, C_PRICE);
            if (p != null) oldPrice.put(str(r, C_LOT), p);
            if (failedEvents.contains(str(r, C_EVENT))) {
                r.set(0, "");
                rows.add(r);
            }
        }

        boolean firstRun = old.isEmpty();
        for (Lot l : lots) {
            Double prev = oldPrice.get(l.lotId());
            String status = firstRun ? "" : prev == null ? "НОВЫЙ" : (l.price() < prev - 0.005 ? "↓ подешевел" : "");
            Event e = l.event();
            rows.add(Arrays.asList(status, e.name(), e.city(), e.date(), e.platform(), l.category(),
                    l.qty(), l.place(), l.price(), l.currency(),
                    l.maxRun() == null ? "" : l.maxRun(), l.runs() == null ? "" : l.runs(),
                    l.url(), now, l.lotId(), e.url()));
        }

        rows.sort(Comparator
                .comparing((List<Object> r) -> str(r, 1))
                .thenComparing(r -> str(r, 3))
                .thenComparing(r -> str(r, 5))
                .thenComparing(r -> num(r, C_PRICE) == null ? 0.0 : num(r, C_PRICE)));

        List<List<Object>> out = new ArrayList<>();
        out.add(HEADER);
        out.addAll(rows);
        sheets.spreadsheets().values().clear(id, q(ACTUAL) + "!A:P", new ClearValuesRequest()).execute();
        sheets.spreadsheets().values().update(id, q(ACTUAL) + "!A1", new ValueRange().setValues(out))
                .setValueInputOption("RAW").execute();

        writeHistory(lots, now);
        writeLog(errors, now);
    }

    private void writeHistory(List<Lot> lots, String now) throws Exception {
        Map<String, List<Lot>> g = lots.stream().collect(Collectors.groupingBy(
                l -> l.event().url() + "\u0000" + l.category() + "\u0000" + l.currency(),
                LinkedHashMap::new, Collectors.toList()));
        List<List<Object>> rows = new ArrayList<>();
        for (List<Lot> list : g.values()) {
            Lot f = list.get(0);
            double min = list.stream().mapToDouble(Lot::price).min().orElse(0);
            int total = list.stream().mapToInt(Lot::qty).sum();
            rows.add(Arrays.asList(now, f.event().name(), f.event().city(), f.event().date(),
                    f.event().platform(), f.category(), min, f.currency(), total));
        }
        append(HISTORY, List.of("Время", "Концерт", "Город", "Дата", "Площадка", "Категория",
                "Мин. цена", "Валюта", "Всего билетов"), rows);
    }

    private void writeLog(List<String[]> errors, String now) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (String[] e : errors) rows.add(Arrays.asList(now, e[0], e[1]));
        append(LOG, List.of("Время", "Событие", "Ошибка"), rows);
    }

    private void append(String sheet, List<Object> header, List<List<Object>> rows) throws Exception {
        if (rows.isEmpty()) return;
        ValueRange head = sheets.spreadsheets().values().get(id, q(sheet) + "!A1").execute();
        List<List<Object>> all = new ArrayList<>();
        if (head.getValues() == null || head.getValues().isEmpty()) all.add(header);
        all.addAll(rows);
        sheets.spreadsheets().values().append(id, q(sheet) + "!A1", new ValueRange().setValues(all))
                .setValueInputOption("RAW").setInsertDataOption("INSERT_ROWS").execute();
    }

    private List<List<Object>> readOld() throws Exception {
        ValueRange vr = sheets.spreadsheets().values().get(id, q(ACTUAL) + "!A2:P")
                .setValueRenderOption("UNFORMATTED_VALUE").execute();
        return vr.getValues() == null ? new ArrayList<>() : vr.getValues();
    }

    private static String q(String sheet) {
        return "'" + sheet + "'";
    }

    private static String str(List<Object> r, int i) {
        return r.size() > i && r.get(i) != null ? r.get(i).toString() : "";
    }

    private static Double num(List<Object> r, int i) {
        if (r.size() <= i || r.get(i) == null) return null;
        Object o = r.get(i);
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(o.toString().replace(",", "."));
        } catch (Exception e) {
            return null;
        }
    }
}
