package io.github.singhalgoru.watchroom;

import org.json.JSONException;
import org.junit.Test;
import static org.junit.Assert.*;

public class DashboardWeatherTest {
    @Test public void forecastUsesExplicitCoordinatesAndCelsius() {
        String url = DashboardWeather.url(28.4595, 77.0266);
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"));
        assertTrue(url.contains("latitude=28.45950"));
        assertTrue(url.contains("longitude=77.02660"));
        assertTrue(url.contains("temperature_unit=celsius"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void latitudeIsBounded() {
        DashboardWeather.url(91, 77);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonFiniteCoordinatesAreRejected() {
        DashboardWeather.url(28, Double.NaN);
    }

    @Test public void currentWeatherIsParsedWithoutAssumingSunshine() throws Exception {
        DashboardWeather.Report report = DashboardWeather.parse(
                "{\"current\":{\"temperature_2m\":25.6,\"weather_code\":63}}");
        assertEquals(26, report.celsius);
        assertEquals(63, report.code);
        assertEquals(R.string.sky_rain, DashboardWeather.description(report.code));
    }

    @Test(expected = JSONException.class)
    public void absentWeatherIsAnError() throws Exception {
        DashboardWeather.parse("{\"error\":true}");
    }

    @Test(expected = JSONException.class)
    public void invalidTemperatureIsAnError() throws Exception {
        DashboardWeather.parse("{\"current\":{\"temperature_2m\":999,\"weather_code\":0}}");
    }

    @Test public void unknownCodeIsNotReplacedWithClear() {
        assertEquals(R.string.sky_unknown, DashboardWeather.description(10));
    }
}
