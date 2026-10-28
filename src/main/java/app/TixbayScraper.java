package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * tixbay.com: страница открывается в браузере, сайт сам запрашивает список предложений
 * (api/v1/ticketGroup). Перехватываем этот ответ: data - массив лотов вида
 * {id, ticketCategorySpecification, areaName, sellingAmount, availableNumbers, salePrice, currencyVO, ...}.
 *
 * ВАЖНО: одна ссылка (eventId) = одна дата. Для каждой даты нужна своя строка в events.csv.
 *
 * availableNumbers - сколько билетов можно взять из лота подряд (например [1,2,3,4]).
 * Сайт ограничивает заказ шестью билетами, поэтому у больших лотов максимум 6.
 */
public class TixbayScraper {

    private static final Pattern EVENT_ID = Pattern.compile("eventId=(\\d+)");
    private static final Pattern ZONE_ROW = Pattern.compile("^(\\S+?)区?\\s+(\\S+?)排\\s+座位隨機$");
    private static final Pattern ZONE_ANYROW = Pattern.compile("^(\\S+?)区?\\s+排數隨機\\s+座位隨機$");

    private final ObjectMapper om = new ObjectMapper();

    public TixbayScraper() {}

    public List<Lot> scrape(Page page, Event ev) throws Exception {
        Matcher em = EVENT_ID.matcher(ev.url());
        String eventId = em.find() ? em.group(1) : null;

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

        Map<String, Lot> lots = new LinkedHashMap<>();
        int listings = 0;
        for (JsonNode root : captured) {
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) continue;
            for (JsonNode o : data) {
                if (!o.isObject() || !o.hasNonNull("id") || !o.has("salePrice") || !o.has("sellingAmount")) continue;
                listings++;
                if (eventId != null && o.hasNonNull("activityEventId")
                    && !eventId.equals(o.get("activityEventId").asText())) continue;
                String id = "tixbay:" + o.path("id").asText();
                lots.putIfAbsent(id, toLot(ev, id, o));
            }
        }

        System.out.println("  tixbay: JSON-ответов " + captured.size() + " "
                           + urls.stream().distinct().limit(10).toList()
                           + "; лотов в ответах " + listings + ", принято " + lots.size());

        if (listings == 0 || lots.isEmpty()) {
            throw new IllegalStateException("tixbay не вернул список предложений (ticketGroup). "
                                            + "Возможна блокировка или изменился формат ответа.");
        }
        return new ArrayList<>(lots.values());
    }

    Lot toLot(Event ev, String id, JsonNode o) {
        int qty = o.path("sellingAmount").asInt(1);
        double price = o.path("salePrice").asDouble();
        String currency = o.path("currencyVO").path("code").asText("HKD");
        String category = o.path("ticketCategorySpecification").asText("").strip();
        String place = place(o.path("areaName").asText(""));

        Integer maxRun;
        String runs;
        JsonNode av = o.get("availableNumbers");
        if (av != null && av.isArray() && av.size() > 0) {
            int max = 0;
            List<String> nums = new ArrayList<>();
            for (JsonNode n : av) {
                max = Math.max(max, n.asInt());
                nums.add(String.valueOf(n.asInt()));
            }
            maxRun = max;
            runs = String.join(", ", nums);
        } else {
            maxRun = null;                         // сайт не дал список вариантов - не утверждаем, что места рядом
            runs = "нет данных";
        }
        return new Lot(ev, id, category, qty, place, price, currency, maxRun, runs, ev.url());
    }

    /** "537区 HH排 座位隨機" -> "Зона 537, ряд HH". */
    static String place(String areaName) {
        String n = areaName.strip();
        if (n.isEmpty() || n.contains("区排座随机")) return "зона и ряд не указаны";
        Matcher m = ZONE_ROW.matcher(n);
        if (m.find()) return "Зона " + m.group(1) + ", ряд " + m.group(2);
        m = ZONE_ANYROW.matcher(n);
        if (m.find()) return "Зона " + m.group(1) + ", ряд не указан";
        return n;
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