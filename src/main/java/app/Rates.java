package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Курсы ЦБ РФ: сколько рублей стоит 1 единица валюты.
 * Пересчёт валюта -> USD -> RUB даёт тот же результат, что и прямой курс ЦБ,
 * поэтому считаем сразу в рубли.
 */
public final class Rates {
    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger(Rates.class);
    static final String URL = "https://www.cbr-xml-daily.ru/daily_json.js";

    private final Map<String, Double> rubPerUnit;

    public Rates(Map<String, Double> rubPerUnit) {
        this.rubPerUnit = new HashMap<>(rubPerUnit);
        this.rubPerUnit.put("RUB", 1.0);
    }

    /**
     * Курсы не получены: стоимость в рублях будет пустой, остальная выгрузка не пострадает.
     */
    public static Rates none() {
        return new Rates(Map.of());
    }

    public static Rates fetchCbr() {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
            Rates r = parseCbr(resp.body());
            LOG.info("rates.ok currencies={} usd={}", r.rubPerUnit.size(), r.rubPerUnit.get("USD"));
            return r;
        } catch (Exception e) {
            LOG.error("rates.failed reason={}", e.toString());
            return none();
        }
    }

    static Rates parseCbr(String json) throws Exception {
        JsonNode valutes = new ObjectMapper().readTree(json).path("Valute");
        Map<String, Double> map = new HashMap<>();
        valutes.fields().forEachRemaining(en -> {
            JsonNode v = en.getValue();
            double nominal = v.path("Nominal").asDouble(1);
            double value = v.path("Value").asDouble();
            if (nominal > 0 && value > 0) map.put(en.getKey(), value / nominal);
        });
        if (!map.containsKey("USD")) throw new IllegalStateException("В ответе ЦБ нет USD");
        return new Rates(map);
    }

    /**
     * Курс в рублях за 1 единицу валюты или null, если валюта неизвестна.
     */
    public Double rub(String currency) {
        return currency == null ? null : rubPerUnit.get(currency.strip().toUpperCase());
    }
}
