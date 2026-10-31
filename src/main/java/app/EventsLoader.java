package app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class EventsLoader {

    public static List<Event> load() throws IOException {
        String file = System.getenv("EVENTS_FILE");
        List<String> lines;
        if (file != null && !file.isBlank()) {
            lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
        } else {
            try (InputStream in = EventsLoader.class.getResourceAsStream("/events.csv")) {
                lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
        }
        List<Event> out = new ArrayList<>();
        for (String line : lines) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("enabled;")) continue;
            String[] p = s.split(";", 7);
            if (p.length < 7) {
                System.err.println("Пропущена некорректная строка в events.csv: " + s);
                continue;
            }
            if (!p[0].strip().equalsIgnoreCase("yes")) continue;
            out.add(new Event(p[1].strip().toLowerCase(), p[2].strip(), p[3].strip(), p[4].strip(), p[5].strip(), p[6].strip()));
        }
        return out;
    }
}
