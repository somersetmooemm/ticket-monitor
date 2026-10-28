package app;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * tixxa.co: список лотов выводится на странице события, по 6 штук на страницу пагинации.
 * Пример: "03 12/26 Zone W3O: Seat OL 08, OL 21 Ticket original price 3,300.00 THB Quantity 2 Tickets
 *          Allow partial purchase SPayLater 10,339.62 THB / Ticket"
 * Цена за 1 билет, без доставки.
 */
public class TixxaScraper {

    private static final Pattern EVENT_ID = Pattern.compile("-(\\d+)/?$");
    // "Tickets?" - раньше было только "Ticket", из-за чего пропадали все лоты с количеством 2 и больше
    private static final Pattern LOT = Pattern.compile(
            "^\\d{2} \\d{2}/\\d{2} (.*?)\\s*Ticket original price ([\\d,.]+) ([A-Z]{3}) "
            + "Quantity (\\d+) Tickets?\\b(.*?)([\\d,]+\\.\\d{2}) ([A-Z]{3}) / Ticket$");
    private static final String FIRST_LOT_JS =
            "prev => { const a = [...document.querySelectorAll('a[href]')]"
            + ".find(x => /-\\d+\\/\\d+$/.test(x.href)); return !a || a.href !== prev; }";
    private static final int MAX_PAGES = 80;

    public List<Lot> scrape(Page page, Event ev) {
        Matcher m = EVENT_ID.matcher(ev.url());
        if (!m.find()) throw new IllegalArgumentException("Не найден id события в URL: " + ev.url());
        Pattern hrefPattern = Pattern.compile("-" + m.group(1) + "/(\\d+)$");

        List<String> jsonUrls = new CopyOnWriteArrayList<>();
        page.onResponse(r -> {
            if (r.headers().getOrDefault("content-type", "").contains("json")) {
                String u = r.url();
                int q = u.indexOf('?');
                jsonUrls.add(q > 0 ? u.substring(0, q) : u);
            }
        });

        page.navigate(ev.url(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(60_000));
        page.waitForTimeout(1500);

        Map<String, Lot> lots = new LinkedHashMap<>();
        int pagesRead = 0;
        for (int p = 1; p <= MAX_PAGES; p++) {
            int before = lots.size();
            String firstHref = firstLotHref(page, hrefPattern);
            collect(page, ev, hrefPattern, lots);
            pagesRead = p;
            if (lots.size() == before) break;                  // новых лотов нет - страницы кончились
            if (!openPage(page, p + 1, firstHref)) break;
        }
        System.out.println("  tixxa: прочитано страниц " + pagesRead + ", лотов " + lots.size()
                           + ", JSON-запросы страницы: " + jsonUrls.stream().distinct().limit(8).toList());
        return new ArrayList<>(lots.values());
    }

    private String firstLotHref(Page page, Pattern hrefPattern) {
        Object res = page.evalOnSelectorAll("a[href]", "els => els.map(e => e.href)");
        for (Object o : (List<?>) res) {
            if (hrefPattern.matcher(String.valueOf(o)).find()) return String.valueOf(o);
        }
        return "";
    }

    private void collect(Page page, Event ev, Pattern hrefPattern, Map<String, Lot> lots) {
        Object res = page.evalOnSelectorAll("a[href]", "els => els.map(e => [e.href, e.innerText])");
        for (Object o : (List<?>) res) {
            List<?> r = (List<?>) o;
            String href = String.valueOf(r.get(0));
            Matcher hm = hrefPattern.matcher(href);
            if (!hm.find()) continue;
            String text = String.valueOf(r.get(1)).replaceAll("\\s+", " ").strip();
            Matcher lm = LOT.matcher(text);
            if (!lm.find()) {
                System.err.println("Не удалось разобрать лот tixxa: " + text);
                continue;
            }
            String place = lm.group(1).strip();
            String category = "номинал " + lm.group(2).replace(",", "") + " " + lm.group(3);
            int qty = Integer.parseInt(lm.group(4));
            double price = Double.parseDouble(lm.group(6).replace(",", ""));
            String id = "tixxa:" + hm.group(1);
            lots.putIfAbsent(id, new Lot(ev, id, category, qty, place, price, lm.group(7), null, null, href));
        }
    }

    /** Пробует несколько способов нажать на страницу n и ждёт, пока список лотов реально обновится. */
    private boolean openPage(Page page, int n, String firstHref) {
        String exact = "^\\s*" + n + "\\s*$";
        List<Locator> candidates = List.of(
                page.getByRole(AriaRole.LISTITEM)
                        .filter(new Locator.FilterOptions().setHasText(Pattern.compile(exact))),
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(String.valueOf(n)).setExact(true)),
                page.getByText(String.valueOf(n), new Page.GetByTextOptions().setExact(true)),
                page.locator("[aria-label*='next' i], button:has-text('›'), button:has-text('>'), li:has-text('›')")
        );
        for (Locator c : candidates) {
            try {
                if (c.count() == 0) continue;
                c.first().click(new Locator.ClickOptions().setTimeout(4_000));
                page.waitForFunction(FIRST_LOT_JS, firstHref,
                        new Page.WaitForFunctionOptions().setTimeout(8_000));
                page.waitForLoadState(LoadState.NETWORKIDLE);
                return true;
            } catch (Exception ignored) {
                // пробуем следующий способ
            }
        }
        System.out.println("  tixxa: не удалось открыть страницу " + n);
        return false;
    }
}