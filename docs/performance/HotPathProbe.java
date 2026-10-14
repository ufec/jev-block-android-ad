import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import me.ethanxu.jevnoisegate.core.common.sms.PendingSmsSuppression;
import me.ethanxu.jevnoisegate.core.fingerprint.TextNormalizer;
import me.ethanxu.jevnoisegate.feature.notification.SmsBatchMatcherKt;
import me.ethanxu.jevnoisegate.feature.notification.SmsNotificationCandidate;
import me.ethanxu.jevnoisegate.core.fingerprint.UrlNormalizer;

/** Calls the repository's compiled Kotlin methods; desktop JVM timings, not Android timings. */
public final class HotPathProbe {
    private static volatile Object sink;
    private static double medianMillis(Runnable run, int warmups, int samples) {
        for (int i = 0; i < warmups; i++) run.run();
        double[] millis = new double[samples];
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            run.run();
            millis[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(millis);
        return millis[samples / 2];
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        for (int i = 0; i < 30; i++) sink = TextNormalizer.INSTANCE.normalize("a".repeat(1024));
        System.out.println("{\"scope\":\"Actual Kotlin methods on desktop JVM; excludes Binder, Android scheduling and disk\","
                + "\"java\":\"" + System.getProperty("java.version") + "\",\"normalizer\":[");
        // A long ASCII token with no dot or URL is a valid negative case for the URL regex.
        for (int size : new int[] {1024, 4096, 8192, 16384}) {
            String text = "a".repeat(size);
            double elapsed = medianMillis(() -> sink = TextNormalizer.INSTANCE.normalize(text), 2, 5);
            int resultChars = ((String)sink).length();
            double urlElapsed = medianMillis(() -> sink = UrlNormalizer.INSTANCE.replace(text), 2, 5);
            System.out.printf("{\"chars\":%d,\"median_ms\":%.4f,\"url_stage_median_ms\":%.4f,\"result_chars\":%d}%s%n",
                    size, elapsed, urlElapsed, resultChars, size == 16384 ? "" : ",");
        }
        String cjk = "中".repeat(16384);
        String shortTokens = ("a".repeat(15) + " ").repeat(1024);
        double cjkMs = medianMillis(() -> sink = TextNormalizer.INSTANCE.normalize(cjk), 10, 20);
        double tokenMs = medianMillis(() -> sink = TextNormalizer.INSTANCE.normalize(shortTokens), 10, 20);
        System.out.printf("],\"controls\":{\"cjk_16384_median_ms\":%.4f,\"short_ascii_tokens_16384_median_ms\":%.4f,"
                + "\"note\":\"Nullable lookup cache behavior is covered by AsyncLookupCacheTest\"},\"sms_batch\":[%n", cjkMs, tokenMs);
        int[][] cases = {{1, 1, 1024}, {128, 32, 1024}, {128, 128, 1024}, {128, 128, 16384}};
        for (int c = 0; c < cases.length; c++) {
            int p = cases[c][0], a = cases[c][1], length = cases[c][2];
            String prefix = "广告正文".repeat((length + 3) / 4).substring(0, length);
            List<PendingSmsSuppression> pending = new ArrayList<>();
            List<SmsNotificationCandidate> active = new ArrayList<>();
            for (int i = 0; i < p; i++) pending.add(new PendingSmsSuppression("10691234", prefix + "pending" + i, 60000));
            for (int i = 0; i < a; i++) active.add(new SmsNotificationCandidate("key" + i, prefix + "shown" + i, "商家广告提醒", false));
            AtomicInteger loads = new AtomicInteger();
            Runnable match = () -> sink = SmsBatchMatcherKt.matchSmsBatch(pending, () -> {
                loads.incrementAndGet(); return active;
            }, () -> 0L, key -> { throw new AssertionError("Negative case must not cancel"); });
            double elapsed = medianMillis(match, 10, 20);
            Runnable freshMatch = () -> {
                List<PendingSmsSuppression> freshPending = new ArrayList<>();
                List<SmsNotificationCandidate> freshActive = new ArrayList<>();
                for (PendingSmsSuppression entry : pending) freshPending.add(new PendingSmsSuppression(
                        entry.getSender(), new String(entry.getBody().toCharArray()), entry.getExpiresAtMs()));
                for (SmsNotificationCandidate candidate : active) freshActive.add(new SmsNotificationCandidate(
                        candidate.getKey(), new String(candidate.getBody().toCharArray()), candidate.getTitle(), candidate.getGrouped()));
                sink = SmsBatchMatcherKt.matchSmsBatch(freshPending, () -> freshActive, () -> 0L,
                        key -> { throw new AssertionError("Negative case must not cancel"); });
            };
            double freshElapsed = medianMillis(freshMatch, 10, 20);
            System.out.printf("{\"pending\":%d,\"active\":%d,\"shared_prefix_chars\":%d,\"cartesian_pairs_avoided\":%d,"
                            + "\"snapshot_loads_per_round\":%.1f,\"median_ms\":%.4f,\"fresh_text_with_copy_median_ms\":%.4f}%s%n",
                    p, a, length, p * a, loads.get() / 30.0, elapsed, freshElapsed, c == cases.length - 1 ? "" : ",");
        }
        System.out.println("]}");
    }
}
