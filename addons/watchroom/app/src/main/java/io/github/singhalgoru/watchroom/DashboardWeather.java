package io.github.singhalgoru.watchroom;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

final class DashboardWeather {
    static String url(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Invalid coordinates");
        }
        return String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                + "&current=temperature_2m,weather_code&temperature_unit=celsius&timezone=auto",
                latitude, longitude);
    }

    static final class Report {
        final int celsius, code;
        Report(int celsius, int code) {
            this.celsius = celsius;
            this.code = code;
        }
    }

    static Report parse(String json) throws JSONException {
        JSONObject current = new JSONObject(json).getJSONObject("current");
        double temperature = current.getDouble("temperature_2m");
        int code = current.getInt("weather_code");
        if (!Double.isFinite(temperature) || temperature < -100 || temperature > 70 || code < 0 || code > 99) {
            throw new JSONException("Invalid current weather");
        }
        return new Report((int) Math.round(temperature), code);
    }

    static int description(int code) {
        if (code == 0) return R.string.sky_clear;
        if (code <= 3) return R.string.sky_cloudy;
        if (code == 45 || code == 48) return R.string.sky_fog;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return R.string.sky_rain;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return R.string.sky_snow;
        if (code >= 95) return R.string.sky_thunder;
        return R.string.sky_unknown;
    }
}
