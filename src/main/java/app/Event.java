package app;

public record Event(String platform, String name, String city, String country, String date, String url) {
    /**
     * Событие без страны (страна в листе клиентов будет пустой).
     */
    public Event(String platform, String name, String city, String date, String url) {
        this(platform, name, city, "", date, url);
    }
}
