package io.github.singhalgoru.watchroom;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

final class Subscription {
    enum Repeat { ONCE, MONTHLY, YEARLY }
    static final int MAX_COUNT = 20;
    final String name;
    final LocalDate anchor;
    final Repeat repeat;

    Subscription(String name, LocalDate anchor, Repeat repeat) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 40
                || anchor == null || anchor.getYear() < 1900 || anchor.getYear() > 9999
                || repeat == null) {
            throw new IllegalArgumentException("Invalid subscription");
        }
        this.name = name.trim();
        this.anchor = anchor;
        this.repeat = repeat;
    }

    LocalDate nextDate(LocalDate today) {
        if (repeat == Repeat.ONCE || !anchor.isBefore(today)) return anchor;
        if (repeat == Repeat.MONTHLY) {
            long months = ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(today));
            LocalDate next = anchor.plusMonths(months);
            return next.isBefore(today) ? anchor.plusMonths(months + 1) : next;
        }
        int years = today.getYear() - anchor.getYear();
        LocalDate next = anchor.plusYears(years);
        return next.isBefore(today) ? anchor.plusYears(years + 1L) : next;
    }

    long daysUntil(LocalDate today) {
        return ChronoUnit.DAYS.between(today, nextDate(today));
    }

    static String encode(List<Subscription> entries) throws JSONException {
        if (entries.size() > MAX_COUNT) throw new IllegalArgumentException("Too many subscriptions");
        JSONArray array = new JSONArray();
        for (Subscription entry : entries) {
            array.put(new JSONObject().put("name", entry.name).put("date", entry.anchor.toString())
                    .put("repeat", entry.repeat.name()));
        }
        return array.toString();
    }

    static List<Subscription> decode(String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        if (array.length() > MAX_COUNT) throw new JSONException("Too many subscriptions");
        List<Subscription> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject row = array.getJSONObject(i);
            if (row.isNull("name") || row.isNull("date") || row.isNull("repeat")) {
                throw new JSONException("Missing saved subscription field at position " + i);
            }
            try {
                result.add(new Subscription(row.getString("name"), LocalDate.parse(row.getString("date")),
                        Repeat.valueOf(row.getString("repeat"))));
            } catch (DateTimeParseException | IllegalArgumentException e) {
                throw new JSONException("Invalid saved subscription at position " + i);
            }
        }
        return result;
    }
}
