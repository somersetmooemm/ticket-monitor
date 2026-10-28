package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * tixbay.com: страница - SPA, данные приходят отдельными JSON-запросами.
 * Открываем страницу в браузере, перехватываем все JSON-ответы и ищем в них массивы объектов с ценой.
 * Названия полей настраиваются в tixbay-mapping.properties.
 */
public class TixbayScraper {

    private record Seat(String category, String section, String row, Integer seatNo,
                        int qty, double price, String currency) {}

    private final ObjectMapper om = new ObjectMapper();
    private final Properties map = new Properties();
    private final Set<String> availableValues = new HashSet<>();

    public TixbayScraper() throws IOException {
        try (InputStream in = TixbayScraper.class.getResourceAsStream("/tixbay-mapping.properties");
             Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            map.load(r);
        }
        for (String s : map.getProperty("available.values", "").split(",")) {
            availableValues.add(s.strip().toLowerCase());
        }
    }

    public List<Lot> scrape(Page page, Event ev) throws Exception {
        List<JsonNode> captured = new CopyOnWriteArrayList<>();
        List<String> urls = new CopyOnWriteArrayList<>();
        Consumer<Response> handler = resp -> {
            try {
                String ct = resp.headers().getOrDefault("content-type", "");
                if (ct.contains("json")) {
                    captured.add(om.readTree(resp.text()));
                    String u = resp.url();
                    int q = u.indexOf('?');
                    urls.add(q > 0 ? u.substring(0, q) : u);
                }
            } catch (Exception ignored) {
                // не JSON или тело недоступно - пропускаем
            }
        };
        page.onResponse(handler);
        page.navigate(ev.url(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(60_000));
        page.waitForTimeout(4000);
        page.offResponse(handler);

        dump(ev, captured);

        List<Seat> seats = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean priceFieldFound = false;
        int withPrice = 0;
        Set<String> statuses = new LinkedHashSet<>();

        for (JsonNode root : captured) {
            List<JsonNode> arrays = new ArrayList<>();
            findArrays(root, arrays);
            for (JsonNode arr : arrays) {
                for (JsonNode o : arr) {
                    if (!o.isObject()) continue;
                    JsonNode p = field(o, "price");
                    Double price = p == null ? null : toDouble(p);
                    if (price == null) continue;
                    priceFieldFound = true;
                    withPrice++;
                    JsonNode stNode = field(o, "status");
                    if (stNode != null && statuses.size() < 10) statuses.add(text(stNode));
                    if (!isAvailable(o)) continue;

                    String section = text(field(o, "section"));
                    String row = text(field(o, "row"));
                    String category = text(field(o, "category"));
                    if (category.isEmpty()) category = section;
                    String currency = text(field(o, "currency"));
                    Integer seatNo = lastNumber(text(field(o, "seat")));
                    Double q = field(o, "qty") == null ? null : toDouble(field(o, "qty"));
                    int qty = q == null ? 1 : Math.max(1, q.intValue());

                    if (seatNo != null) {
                        String key = String.join("|", category, section, row, String.valueOf(seatNo),
                                String.valueOf(price), currency);
                        if (!seen.add(key)) continue;
                    }
                    seats.add(new Seat(category, section, row, seatNo, qty, price, currency));
                }
            }
        }

        System.out.println("  tixbay: JSON-ответов " + captured.size() + " "
                           + urls.stream().distinct().limit(10).toList()
                           + "; объектов с ценой " + withPrice + ", свободных " + seats.size()
                           + ", значения status: " + statuses);

        if (!priceFieldFound) {
            throw new IllegalStateException("В JSON-ответах tixbay не найдено поле с ценой. "
                                            + "Проверьте tixbay-mapping.properties и артефакт debug.");
        }
        return toLots(ev, seats);
    }

    private List<Lot> toLots(Event ev, List<Seat> seats) {
        Map<String, List<Seat>> groups = new LinkedHashMap<>();
        for (Seat s : seats) {
            String key = String.join("|", s.category(), s.section(), s.row(),
                    String.valueOf(s.price()), s.currency());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        List<Lot> lots = new ArrayList<>();
        for (Map.Entry<String, List<Seat>> en : groups.entrySet()) {
            List<Seat> g = en.getValue();
            Seat f = g.get(0);
            String id = "tixbay:" + ev.url() + "#" + en.getKey();
            String where = (f.section().isEmpty() ? "" : "Сек. " + f.section())
                           + (f.row().isEmpty() ? "" : ", ряд " + f.row());
            List<Integer> nums = g.stream().map(Seat::seatNo).filter(Objects::nonNull).toList();

            if (!nums.isEmpty() && nums.size() == g.size() && !f.row().isEmpty()) {
                Adjacency.Result r = Adjacency.analyze(nums);
                lots.add(new Lot(ev, id, f.category(), nums.size(), where + ", места " + r.ranges(),
                        f.price(), f.currency(), r.maxRun(), r.variants(), ev.url()));
            } else {
                int qty = g.stream().mapToInt(Seat::qty).sum();
                lots.add(new Lot(ev, id, f.category(), qty, where.isEmpty() ? "-" : where,
                        f.price(), f.currency(), null, null, ev.url()));
            }
        }
        return lots;
    }

    // ---------- вспомогательное ----------

    private void findArrays(JsonNode n, List<JsonNode> out) {
        if (n.isArray()) {
            if (n.size() > 0 && n.get(0).isObject()) out.add(n);
            for (JsonNode c : n) findArrays(c, out);
        } else if (n.isObject()) {
            n.elements().forEachRemaining(c -> findArrays(c, out));
        }
    }

    private JsonNode field(JsonNode obj, String key) {
        for (String cand : map.getProperty(key, "").split(",")) {
            String c = cand.strip();
            if (c.isEmpty()) continue;
            for (Iterator<String> it = obj.fieldNames(); it.hasNext(); ) {
                String f = it.next();
                if (f.equalsIgnoreCase(c) && !obj.get(f).isNull()) return obj.get(f);
            }
        }
        return null;
    }

    private boolean isAvailable(JsonNode o) {
        JsonNode st = field(o, "status");
        return st == null || availableValues.contains(text(st).toLowerCase());
    }

    private static String text(JsonNode n) {
        if (n == null) return "";
        return n.isValueNode() ? n.asText().strip() : n.toString();
    }

    private static Double toDouble(JsonNode n) {
        if (n.isNumber()) return n.asDouble();
        try {
            return Double.parseDouble(n.asText().replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer lastNumber(String s) {
        Matcher m = Pattern.compile("(\\d+)\\D*$").matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private void dump(Event ev, List<JsonNode> captured) throws IOException {
        if (!"1".equals(System.getenv("DEBUG_DUMP"))) return;
        Path dir = Path.of("debug");
        Files.createDirectories(dir);
        String base = Integer.toHexString(ev.url().hashCode());
        Files.writeString(dir.resolve(base + "-url.txt"), ev.url());
        for (int i = 0; i < captured.size(); i++) {
            Files.writeString(dir.resolve(base + "-" + i + ".json"),
                    om.writerWithDefaultPrettyPrinter().writeValueAsString(captured.get(i)));
        }
    }
}