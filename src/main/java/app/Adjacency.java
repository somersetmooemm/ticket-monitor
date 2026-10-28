package app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Поиск непрерывных цепочек свободных мест в ряду. */
public final class Adjacency {

    public record Result(int maxRun, String variants, String ranges) {}

    private Adjacency() {}

    public static Result analyze(Collection<Integer> seats) {
        List<Integer> s = seats.stream().distinct().sorted().toList();
        List<int[]> runs = new ArrayList<>();
        int start = s.get(0), prev = start;
        for (int i = 1; i < s.size(); i++) {
            int x = s.get(i);
            if (x == prev + 1) {
                prev = x;
            } else {
                runs.add(new int[]{start, prev});
                start = x;
                prev = x;
            }
        }
        runs.add(new int[]{start, prev});

        int max = 0;
        StringBuilder ranges = new StringBuilder();
        for (int[] r : runs) {
            max = Math.max(max, r[1] - r[0] + 1);
            if (ranges.length() > 0) ranges.append(", ");
            ranges.append(r[0] == r[1] ? String.valueOf(r[0]) : r[0] + "-" + r[1]);
        }
        // сколько блоков подряд длиной не меньше N
        StringBuilder v = new StringBuilder();
        for (int n = 1; n <= 4; n++) {
            final int need = n;
            long cnt = runs.stream().filter(r -> r[1] - r[0] + 1 >= need).count();
            if (n > 1) v.append(" | ");
            v.append(n == 4 ? "4+" : String.valueOf(n)).append(":").append(cnt);
        }
        return new Result(max, v.toString(), ranges.toString());
    }
}
