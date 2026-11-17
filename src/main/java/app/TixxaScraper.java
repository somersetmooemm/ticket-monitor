package app;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * tixxa.co: список лотов выводится на странице события,
 * по 6 штук на страницу. Страницы открываются по URL ?page=N.
 * Конец выгрузки определяется по пустой странице ("No tickets available yet").
 * Цена за 1 билет, без доставки.
 */
public class TixxaScraper {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger(TixxaScraper.class);

    private static final int OPEN_RETRIES = 3;
    private static final int MAX_PAGES = 80;

    private static final String EMPTY_TEXT = "No tickets available yet";

    private static final Pattern EVENT_ID =
            Pattern.compile("-(\\d+)/?$");

    private static final Pattern LOT = Pattern.compile(
            "^\\d{2} \\d{2}/\\d{2} (.*?)\\s*Ticket original price ([\\d,.]+) ([A-Z]{3}) "
            + "Quantity (\\d+) Tickets?\\b(.*?)([\\d,]+\\.\\d{2}) ([A-Z]{3}) / Ticket$");

    /**
     * Ждём, пока на странице появятся ссылки на лоты либо заглушка "нет билетов".
     */
    private static final String LISTING_READY_JS =
            "src => { const re = new RegExp(src);"
            + " if ((document.body.innerText || '').includes('" + EMPTY_TEXT + "')) return true;"
            + " return [...document.querySelectorAll('a[href]')].some(a => re.test(a.href)); }";

    /**
     * Исключение с сохранением уже собранных лотов.
     */
    public static class PartialScrapeException extends RuntimeException {

        private final List<Lot> partialLots;

        public PartialScrapeException(
                String message,
                Throwable cause,
                List<Lot> partialLots
        ) {
            super(message, cause);
            this.partialLots = List.copyOf(partialLots);
        }

        public List<Lot> getPartialLots() {
            return partialLots;
        }
    }

    /**
     * Состояние страницы до сбора лотов.
     */
    enum PageState {
        /** На странице есть лоты, собираем. */
        CONTINUE,
        /** Пустая страница после непустых: выгрузка закончена. */
        FINISH,
        /** Пустая первая страница: у события нет лотов. */
        EMPTY_EVENT
    }

    /**
     * Решение по странице. Лоты важнее заглушки; страница без лотов и без
     * заглушки считается недогруженной, а не последней.
     */
    static PageState classifyPage(int pageNumber, boolean emptyMarker, int lotLinks) {

        if (lotLinks > 0) {
            return PageState.CONTINUE;
        }

        if (emptyMarker) {
            return pageNumber == 1 ? PageState.EMPTY_EVENT : PageState.FINISH;
        }

        throw new IllegalStateException(
                "tixxa: страница " + pageNumber
                + " без лотов и без заглушки; сбор неполный"
        );
    }

    /**
     * Сайт вернул ту же страницу вместо пустой: новых лотов нет.
     */
    static boolean isRepeatedPage(int pageNumber, int added) {
        return pageNumber > 1 && added == 0;
    }

    static String pageUrl(String baseUrl, int n) {
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "page=" + n;
    }

    public List<Lot> scrape(Page page, Event ev) {

        Matcher m = EVENT_ID.matcher(ev.url());

        if (!m.find()) {
            throw new IllegalArgumentException(
                    "Не найден id события в URL: " + ev.url()
            );
        }

        Pattern hrefPattern =
                Pattern.compile("-" + m.group(1) + "/(\\d+)$");

        List<String> jsonUrls = new CopyOnWriteArrayList<>();

        Consumer<com.microsoft.playwright.Response> listener = r -> {
            if (r.headers().getOrDefault("content-type", "").contains("json")) {
                String u = r.url();
                int q = u.indexOf('?');
                jsonUrls.add(q > 0 ? u.substring(0, q) : u);
            }
        };

        page.onResponse(listener);

        Map<String, Lot> lots = new LinkedHashMap<>();

        int pagesRead = 0;
        boolean finished = false;

        try {

            page.navigate(
                    ev.url(),
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.NETWORKIDLE)
                            .setTimeout(60_000)
            );

            waitForListing(page, hrefPattern);

            for (int p = 1; p <= MAX_PAGES; p++) {

                int onPage = countLotLinks(page, hrefPattern);

                PageState state = classifyPage(p, isEmptyPage(page), onPage);

                if (state == PageState.EMPTY_EVENT) {
                    LOG.info("tixxa.empty event={}", ev.date());
                    return List.of();
                }

                if (state == PageState.FINISH) {

                    LOG.info(
                            "tixxa.pagination.end event={} page={} reason=empty-page",
                            ev.date(), p
                    );

                    finished = true;
                    break;
                }

                int before = lots.size();

                int invalid = collect(page, ev, hrefPattern, lots);

                if (invalid > 0) {
                    throw new IllegalStateException(
                            "tixxa: не разобрано лотов на странице "
                            + p + ": " + invalid
                    );
                }

                pagesRead = p;

                int added = lots.size() - before;

                LOG.info(
                        "tixxa.page event={} page={} onPage={} added={} total={} advertisedLast={}",
                        ev.date(), p, onPage, added, lots.size(), lastPageNumber(page)
                );

                // Сайт вернул ту же страницу вместо пустой: вышли за предел
                if (isRepeatedPage(p, added)) {

                    LOG.info(
                            "tixxa.pagination.end event={} page={} reason=no-new-lots",
                            ev.date(), p
                    );

                    finished = true;
                    break;
                }

                if (!openPage(page, ev.url(), p + 1, hrefPattern)) {
                    throw new IllegalStateException(
                            "tixxa: не удалось открыть страницу " + (p + 1)
                            + "; прочитано " + p
                            + ", лотов " + lots.size()
                    );
                }
            }

            if (!finished || lots.isEmpty()) {
                throw new IllegalStateException(
                        "tixxa: не удалось подтвердить полную выгрузку; страниц "
                        + pagesRead
                );
            }

        } catch (RuntimeException ex) {

            if (lots.isEmpty()) {
                throw ex;
            }

            List<Lot> partial = new ArrayList<>(lots.values());

            LOG.error(
                    "tixxa.partial event={} collected={} reason={}",
                    ev.date(),
                    partial.size(),
                    ex.toString()
            );

            throw new PartialScrapeException(
                    "Неполная выгрузка: " + partial.size()
                    + " лотов. " + ex.getMessage(),
                    ex,
                    partial
            );

        } finally {
            page.offResponse(listener);
        }

        LOG.info(
                "tixxa.complete event={} pages={} lots={} jsonResponseCount={}",
                ev.date(),
                pagesRead,
                lots.size(),
                jsonUrls.size()
        );

        return new ArrayList<>(lots.values());
    }

    /**
     * Номер последней страницы по ссылкам ?page=N. Только для логов:
     * на завершение выгрузки не влияет.
     */
    private int lastPageNumber(Page page) {

        try {
            Object result = page.evaluate("""
                    () => Array.from(document.querySelectorAll('a[href*="page="]'))
                        .map(a => {
                            const m = a.href.match(/[?&]page=(\\d+)/);
                            return m ? Number(m[1]) : 0;
                        })
                    """);

            int max = 0;

            if (result instanceof List<?> numbers) {
                for (Object value : numbers) {
                    if (value instanceof Number n) {
                        max = Math.max(max, n.intValue());
                    }
                }
            }

            return max;

        } catch (Exception ex) {
            return 0;
        }
    }

    private boolean isEmptyPage(Page page) {

        return Boolean.TRUE.equals(page.evaluate(
                "() => (document.body.innerText || '').includes('" + EMPTY_TEXT + "')"
        ));
    }

    private void waitForListing(Page page, Pattern hrefPattern) {

        try {
            page.waitForFunction(
                    LISTING_READY_JS,
                    hrefPattern.pattern(),
                    new Page.WaitForFunctionOptions().setTimeout(10_000)
            );
        } catch (Exception ex) {
            LOG.warn(
                    "tixxa.listing.wait.failed url={} reason={}",
                    page.url(),
                    shortReason(ex)
            );
        }
    }

    /**
     * Количество уникальных ссылок на лоты на текущей странице.
     */
    private int countLotLinks(Page page, Pattern hrefPattern) {

        Object res = page.evalOnSelectorAll(
                "a[href]",
                "els => els.map(e => e.href)"
        );

        Set<String> unique = new HashSet<>();

        for (Object o : (List<?>) res) {
            String href = String.valueOf(o);

            if (hrefPattern.matcher(href).find()) {
                unique.add(href);
            }
        }

        return unique.size();
    }

    private int collect(
            Page page,
            Event ev,
            Pattern hrefPattern,
            Map<String, Lot> lots
    ) {

        int invalid = 0;

        Object res = page.evalOnSelectorAll(
                "a[href]",
                "els => els.map(e => [e.href, e.innerText])"
        );

        for (Object o : (List<?>) res) {

            List<?> r = (List<?>) o;

            String href = String.valueOf(r.get(0));

            Matcher hm = hrefPattern.matcher(href);

            if (!hm.find()) {
                continue;
            }

            String text = String.valueOf(r.get(1))
                    .replaceAll("\\s+", " ")
                    .strip();

            Matcher lm = LOT.matcher(text);

            if (!lm.find()) {

                LOG.warn(
                        "tixxa.invalidListing event={} pageTextLength={}",
                        ev.date(),
                        text.length()
                );

                invalid++;
                continue;
            }

            Lot lot = parseLot(ev, href, text);

            lots.putIfAbsent(lot.lotId(), lot);
        }

        return invalid;
    }

    static Lot parseLot(Event ev, String href, String text) {

        Matcher eventMatch = EVENT_ID.matcher(ev.url());

        if (!eventMatch.find()) {
            throw new IllegalArgumentException(
                    "Не найден id события в URL"
            );
        }

        Matcher hrefMatch = Pattern.compile(
                "-" + eventMatch.group(1) + "/(\\d+)$"
        ).matcher(href);

        if (!hrefMatch.find()) {
            throw new IllegalArgumentException(
                    "Ссылка на лот не относится к событию: " + href
            );
        }

        Matcher m = LOT.matcher(
                text.replaceAll("\\s+", " ").strip()
        );

        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Не удалось разобрать лот: " + text
            );
        }

        return new Lot(
                ev,
                "tixxa:" + hrefMatch.group(1),
                "номинал " + m.group(2).replace(",", "")
                + " " + m.group(3),
                Integer.parseInt(m.group(4)),
                m.group(1).strip(),
                Double.parseDouble(m.group(6).replace(",", "")),
                m.group(7),
                null,
                null,
                href
        );
    }

    /**
     * Открытие страницы N по URL ?page=N с повторными попытками.
     */
    private boolean openPage(
            Page page,
            String baseUrl,
            int n,
            Pattern hrefPattern
    ) {

        String url = pageUrl(baseUrl, n);

        for (int attempt = 1; attempt <= OPEN_RETRIES; attempt++) {

            LOG.info(
                    "tixxa.navigation targetPage={} attempt={}/{}",
                    n, attempt, OPEN_RETRIES
            );

            try {

                page.navigate(
                        url,
                        new Page.NavigateOptions()
                                .setWaitUntil(WaitUntilState.NETWORKIDLE)
                                .setTimeout(30_000)
                );

                waitForListing(page, hrefPattern);

                LOG.info("tixxa.navigation.ok targetPage={}", n);

                return true;

            } catch (Exception ex) {

                LOG.warn(
                        "tixxa.navigation.failed page={} attempt={} reason={}",
                        n,
                        attempt,
                        shortReason(ex)
                );

                page.waitForTimeout(1000L * attempt);
            }
        }

        LOG.error("tixxa.navigation.exhausted page={}", n);

        return false;
    }

    private static String shortReason(Exception ex) {

        String message = ex.getMessage();

        if (message == null) {
            return ex.getClass().getSimpleName();
        }

        String firstLine = message.split("\\R", 2)[0];

        return ex.getClass().getSimpleName()
               + ": "
               + firstLine.substring(
                0,
                Math.min(firstLine.length(), 350)
        );
    }
}