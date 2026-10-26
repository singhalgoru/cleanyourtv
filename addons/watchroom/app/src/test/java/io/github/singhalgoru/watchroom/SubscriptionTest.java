package io.github.singhalgoru.watchroom;

import org.json.JSONException;
import org.junit.Test;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SubscriptionTest {
    private Subscription entry(String date, Subscription.Repeat repeat) {
        return new Subscription("Test service", LocalDate.parse(date), repeat);
    }

    @Test public void monthlyClampsWithoutLosingOriginalDay() {
        Subscription subscription = entry("2026-01-31", Subscription.Repeat.MONTHLY);
        assertEquals(LocalDate.parse("2026-02-28"), subscription.nextDate(LocalDate.parse("2026-02-01")));
        assertEquals(LocalDate.parse("2026-03-31"), subscription.nextDate(LocalDate.parse("2026-03-01")));
    }

    @Test public void leapYearRecurrenceKeepsOriginalAnchor() {
        Subscription subscription = entry("2024-02-29", Subscription.Repeat.YEARLY);
        assertEquals(LocalDate.parse("2025-02-28"), subscription.nextDate(LocalDate.parse("2025-01-01")));
        assertEquals(LocalDate.parse("2028-02-29"), subscription.nextDate(LocalDate.parse("2028-01-01")));
    }

    @Test public void dueTodayIsNotSkipped() {
        Subscription subscription = entry("2026-01-31", Subscription.Repeat.MONTHLY);
        assertEquals(0, subscription.daysUntil(LocalDate.parse("2026-02-28")));
    }

    @Test public void recurringDateAdvancesAfterDueDay() {
        Subscription subscription = entry("2026-01-31", Subscription.Repeat.MONTHLY);
        assertEquals(LocalDate.parse("2026-03-31"), subscription.nextDate(LocalDate.parse("2026-03-01")));
    }

    @Test public void oneTimePastDateRemainsPast() {
        Subscription subscription = entry("2026-10-20", Subscription.Repeat.ONCE);
        assertEquals(-6, subscription.daysUntil(LocalDate.parse("2026-10-26")));
    }

    @Test public void futureAnchorsAreNotMovedBackwards() {
        Subscription subscription = entry("2027-12-31", Subscription.Repeat.MONTHLY);
        assertEquals(LocalDate.parse("2027-12-31"), subscription.nextDate(LocalDate.parse("2026-01-01")));
    }

    @Test public void roundTripKeepsNameDateAndRepeat() throws Exception {
        Subscription original = new Subscription(" Service \"A\" ", LocalDate.parse("2026-11-02"),
                Subscription.Repeat.MONTHLY);
        Subscription restored = Subscription.decode(Subscription.encode(List.of(original))).get(0);
        assertEquals("Service \"A\"", restored.name);
        assertEquals(original.anchor, restored.anchor);
        assertEquals(original.repeat, restored.repeat);
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankNameIsRejected() {
        new Subscription(" ", LocalDate.now(), Subscription.Repeat.ONCE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void longNameIsRejected() {
        new Subscription("a".repeat(41), LocalDate.now(), Subscription.Repeat.ONCE);
    }

    @Test(expected = JSONException.class)
    public void invalidSavedDateIsNotSilentlyDiscarded() throws Exception {
        Subscription.decode("[{\"name\":\"Test\",\"date\":\"2026-02-30\",\"repeat\":\"MONTHLY\"}]");
    }

    @Test(expected = JSONException.class)
    public void unknownRepeatIsNotSilentlyDefaulted() throws Exception {
        Subscription.decode("[{\"name\":\"Test\",\"date\":\"2026-02-01\",\"repeat\":\"UNKNOWN\"}]");
    }

    @Test(expected = JSONException.class)
    public void nullNameIsNotAcceptedAsText() throws Exception {
        Subscription.decode("[{\"name\":null,\"date\":\"2026-02-01\",\"repeat\":\"ONCE\"}]");
    }

    @Test(expected = IllegalArgumentException.class)
    public void countIsBounded() throws Exception {
        List<Subscription> entries = new ArrayList<>();
        for (int i = 0; i <= Subscription.MAX_COUNT; i++) {
            entries.add(entry("2026-10-26", Subscription.Repeat.ONCE));
        }
        Subscription.encode(entries);
    }
}
