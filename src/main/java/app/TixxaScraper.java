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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * tixxa.co: список лотов выводится прямо на странице события, по 6 штук на страницу пагинации.
 * Пример текста лота:
 * "03 12/26 Zone W3A row AD Ticket original price 3,300.00 THB Quantity 1 Ticket ... 9,914.27 THB / Ticket"
 * Цена указана без доставки.
 */
public class TixxaScraper {

    private static final Pattern EVENT_ID = Pattern.compile("-(\\d+)/?$");
    private static final Pattern LOT = Pattern.compile(
            "^\\d{2} \\d{2}/\\d{2} (.*?)\\s*Ticket original price ([\\d,.]+) ([A-Z]{3}) "
            + "Quantity (\\d+) Ticket\\b(.*?)([\\d,]+\\.\\d{2}) ([A-Z]{3}) / Ticket$");
    private static final int MAX_PAGES = 80;

    public List<Lot> scrape(Page page, Event ev) {
        Matcher m = EVENT_ID.matcher(ev.url());
        if (!m.find()) throw new IllegalArgumentException("Не найден id события в URL: " + ev.url());
        Pattern hrefPattern = Pattern.compile("-" + m.group(1) + "/(\\d+)$");

        page.navigate(ev.url(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(60_000));
        page.waitForTimeout(1500);

        Map<String, Lot> lots = new LinkedHashMap<>();
        for (int p = 1; p <= MAX_PAGES; p++) {
            int before = lots.size();
            collect(page, ev, hrefPattern, lots);
            if (lots.size() == before) break;          // новых лотов нет - страницы кончились
            if (!openPage(page, p + 1)) break;
        }
        return new ArrayList<>(lots.values());
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

    private boolean openPage(Page page, int n) {
        Locator li = page.getByRole(AriaRole.LISTITEM)
                .filter(new Locator.FilterOptions().setHasText(Pattern.compile("^\\s*" + n + "\\s*$")));
        if (li.count() == 0) return false;
        li.first().click(new Locator.ClickOptions().setTimeout(5_000));
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.waitForTimeout(1200);
        return true;
    }
}
