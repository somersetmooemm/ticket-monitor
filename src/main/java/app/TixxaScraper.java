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
    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger(TixxaScraper.class);
    private static final int OPEN_RETRIES = 3;


    private static final Pattern EVENT_ID = Pattern.compile("-(\\d+)/?$");
    // "Tickets?" - раньше было только "Ticket", из-за чего пропадали все лоты с количеством 2 и больше
    private static final Pattern LOT = Pattern.compile(
            "^\\d{2} \\d{2}/\\d{2} (.*?)\\s*Ticket original price ([\\d,.]+) ([A-Z]{3}) "
            + "Quantity (\\d+) Tickets?\\b(.*?)([\\d,]+\\.\\d{2}) ([A-Z]{3}) / Ticket$");
    private static final String FIRST_LOT_JS =
            "prev => { const a = [...document.querySelectorAll('a[href]')]"
            + ".find(x => /-\\d+\\/\\d+$/.test(x.href)); return !a || a.href !== prev; }";
    private static final int MAX_PAGES = 80;
    private static final int PAGE_SIZE = 6;

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
        boolean finished = false;
        for (int p = 1; p <= MAX_PAGES; p++) {
            int before = lots.size();
            String firstHref = firstLotHref(page, hrefPattern);
            int invalid = collect(page, ev, hrefPattern, lots);
            if (invalid > 0) throw new IllegalStateException("tixxa: не разобрано лотов на странице " + p + ": " + invalid);
            pagesRead = p;
            int added = lots.size() - before;
            requireNewLots(p, added);
            int advertisedLast = lastPageNumber(page);
            if (advertisedLast > MAX_PAGES) {
                throw new IllegalStateException("tixxa: страниц больше лимита " + MAX_PAGES);
            }
            LOG.info("tixxa.page event={} page={} added={} total={} advertisedLast={}", ev.date(), p, added, lots.size(), advertisedLast);
            if (advertisedLast > 0 && p >= advertisedLast) {
                finished = true;
                break;
            }
            // Малое количество новых лотов не доказывает конец выдачи.
            // При отсутствии явной пагинации сохраняем старую таблицу вместо публикации неполных данных.
            if (advertisedLast == 0) {
                throw new IllegalStateException("tixxa: пагинация не подтверждена на странице " + p);
            }
            if (!openPage(page, p + 1, firstHref)) {
                // Без явного подтверждения конца выдачи неполный результат нельзя публиковать.
                throw new IllegalStateException("tixxa: не удалось открыть страницу " + (p + 1)
                        + "; прочитано " + p + ", лотов " + lots.size());
            }
        }
        if (!finished || lots.isEmpty()) {
            throw new IllegalStateException("tixxa: не удалось подтвердить полную выгрузку; страниц " + pagesRead);
        }
        LOG.info("tixxa.complete event={} pages={} lots={} jsonResponseCount={}", ev.date(), pagesRead, lots.size(), jsonUrls.size());
        return new ArrayList<>(lots.values());
    }

    /** Номер последней страницы, когда сайт явно показывает цифровую пагинацию. */
    private int lastPageNumber(Page page) {
        Object result = page.evaluate("""
                () => Array.from(document.querySelectorAll('nav button, nav a, [class*=pagination] button, [class*=pagination] a, [aria-label*=pagination] button'))
                  .map(x => (x.textContent || '').trim())
                  .filter(x => /^\\d+$/.test(x))
                  .map(Number)
                """);
        int max = 0;
        if (result instanceof List<?> numbers) {
            for (Object value : numbers) {
                if (value instanceof Number n) max = Math.max(max, n.intValue());
            }
        }
        return max;
    }

    private String firstLotHref(Page page, Pattern hrefPattern) {
        Object res = page.evalOnSelectorAll("a[href]", "els => els.map(e => e.href)");
        for (Object o : (List<?>) res) {
            if (hrefPattern.matcher(String.valueOf(o)).find()) return String.valueOf(o);
        }
        return "";
    }

    private int collect(Page page, Event ev, Pattern hrefPattern, Map<String, Lot> lots) {
        int invalid = 0;
        Object res = page.evalOnSelectorAll("a[href]", "els => els.map(e => [e.href, e.innerText])");
        for (Object o : (List<?>) res) {
            List<?> r = (List<?>) o;
            String href = String.valueOf(r.get(0));
            Matcher hm = hrefPattern.matcher(href);
            if (!hm.find()) continue;
            String text = String.valueOf(r.get(1)).replaceAll("\\s+", " ").strip();
            Matcher lm = LOT.matcher(text);
            if (!lm.find()) {
                LOG.warn("tixxa.invalidListing event={} pageTextLength={}", ev.date(), text.length());
                invalid++;
                continue;
            }
            Lot lot = parseLot(ev, href, text);
            lots.putIfAbsent(lot.lotId(), lot);
        }
        return invalid;
    }

    static void requireNewLots(int pageNumber, int added) {
        if (added <= 0) throw new IllegalStateException("tixxa: страница " + pageNumber
                + " не дала новых лотов; сбор неполный");
    }

    static Lot parseLot(Event ev, String href, String text) {
        Matcher eventMatch = EVENT_ID.matcher(ev.url());
        if (!eventMatch.find()) throw new IllegalArgumentException("Не найден id события в URL");
        Matcher hrefMatch = Pattern.compile("-" + eventMatch.group(1) + "/(\\d+)$").matcher(href);
        if (!hrefMatch.find()) throw new IllegalArgumentException("Ссылка на лот не относится к событию: " + href);
        Matcher m = LOT.matcher(text.replaceAll("\\s+", " ").strip());
        if (!m.matches()) throw new IllegalArgumentException("Не удалось разобрать лот: " + text);
        return new Lot(ev, "tixxa:" + hrefMatch.group(1),
                "номинал " + m.group(2).replace(",", "") + " " + m.group(3),
                Integer.parseInt(m.group(4)), m.group(1).strip(),
                Double.parseDouble(m.group(6).replace(",", "")), m.group(7), null, null, href);
    }

    /** Повторные попытки перехода на страницу. Перед retry возвращаемся к известной странице. */
    private boolean openPage(Page page, int n, String previousHref) {
        for (int attempt = 1; attempt <= OPEN_RETRIES; attempt++) {
            LOG.info("tixxa.navigation targetPage={} attempt={}/{}", n, attempt, OPEN_RETRIES);
            if (attempt > 1) {
                try {
                    page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));
                    // После reload страница может вернуться на первую страницу.
                    // В таком случае не выдаём ложного подтверждения перехода.
                } catch (Exception ex) {
                    LOG.warn("tixxa.reload.failed page={} attempt={} reason={}", n, attempt, ex.toString());
                }
            }
            if (tryOpenPage(page, n, previousHref)) return true;
            LOG.warn("tixxa.navigation.failed page={} attempt={}", n, attempt);
            page.waitForTimeout(1000L * attempt);
        }
        LOG.error("tixxa.navigation.exhausted page={}", n);
        return false;
    }

    private boolean tryOpenPage(Page page, int n, String firstHref) {
        String exact = "^\\s*" + n + "\\s*$";
        List<Locator> candidates = List.of(
                page.getByRole(AriaRole.LISTITEM).filter(new Locator.FilterOptions().setHasText(Pattern.compile(exact))),
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(String.valueOf(n)).setExact(true)),
                page.getByText(String.valueOf(n), new Page.GetByTextOptions().setExact(true)),
                page.locator("[aria-label*='next' i], button:has-text('›'), button:has-text('>'), li:has-text('›')")
        );
        for (int i=0; i<candidates.size(); i++) {
            try {
                Locator c = candidates.get(i);
                if (c.count() == 0) continue;
                c.first().click(new Locator.ClickOptions().setTimeout(4_000));
                page.waitForFunction(FIRST_LOT_JS, firstHref, new Page.WaitForFunctionOptions().setTimeout(8_000));
                page.waitForLoadState(LoadState.NETWORKIDLE);
                LOG.info("tixxa.navigation.ok targetPage={} strategy={}", n, i);
                return true;
            } catch (Exception ex) {
                LOG.debug("tixxa.navigation.strategy.failed page={} strategy={} reason={}", n, i, ex.toString());
            }
        }
        return false;
    }
}
