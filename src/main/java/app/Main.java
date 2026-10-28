package app;

import com.microsoft.playwright.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class Main {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static void main(String[] args) throws Exception {
        List<Event> events = EventsLoader.load();
        System.out.println("Событий к проверке: " + events.size());

        SheetsWriter sheets = new SheetsWriter();

        List<Lot> all = new ArrayList<>();
        Set<String> failed = new HashSet<>();
        List<String[]> errors = new ArrayList<>();

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            TixxaScraper tixxa = new TixxaScraper();
            TixbayScraper tixbay = new TixbayScraper();

            for (Event e : events) {
                String label = e.name() + " " + e.city() + " " + e.date() + " [" + e.platform() + "]";
                BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent(UA).setLocale("en-US").setViewportSize(1400, 1000));
                Page page = ctx.newPage();
                try {
                    List<Lot> lots = switch (e.platform()) {
                        case "tixxa" -> tixxa.scrape(page, e);
                        case "tixbay" -> tixbay.scrape(page, e);
                        default -> throw new IllegalArgumentException("Неизвестная площадка: " + e.platform());
                    };
                    System.out.println(label + ": лотов " + lots.size());
                    all.addAll(lots);
                } catch (Exception ex) {
                    System.err.println(label + ": ОШИБКА " + ex);
                    failed.add(e.url());
                    errors.add(new String[]{label, String.valueOf(ex.getMessage())});
                } finally {
                    ctx.close();
                }
                Thread.sleep(2000 + ThreadLocalRandom.current().nextInt(3000));
            }
            browser.close();
        }

        sheets.write(all, failed, errors);
        System.out.println("Записано лотов: " + all.size() + ", ошибок: " + errors.size());

        // Если упали все события - считаем запуск неуспешным, GitHub пришлёт письмо
        if (!events.isEmpty() && failed.size() == events.size()) System.exit(1);
    }
}
