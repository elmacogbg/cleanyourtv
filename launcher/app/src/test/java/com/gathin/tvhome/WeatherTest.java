package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Weather is a port of Njord's njord.js; these pin the behaviour that makes the TV show Njord's numbers. */
public class WeatherTest {

    // YR is grouped by its UTC timestamps, as njord.js slices them: 22:00Z on the 21st belongs to the 21st.
    private static final String YR = "{\"properties\":{\"timeseries\":["
            + "{\"time\":\"2026-10-21T22:00:00Z\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":20,\"wind_speed\":1,\"wind_from_direction\":0,\"relative_humidity\":50}},"
            + "\"next_1_hours\":{\"details\":{\"precipitation_amount\":5}}}},"
            + "{\"time\":\"2026-10-22T01:00:00Z\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":8.4,\"wind_speed\":4,\"wind_from_direction\":180,\"relative_humidity\":88}},"
            + "\"next_1_hours\":{\"summary\":{\"symbol_code\":\"cloudy\"},\"details\":{\"precipitation_amount\":0.2}},"
            + "\"next_6_hours\":{\"summary\":{\"symbol_code\":\"lightrain\"},\"details\":{\"precipitation_amount\":1.0}}}},"
            + "{\"time\":\"2026-10-22T13:00:00Z\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":12.5,\"wind_speed\":7.4,\"wind_from_direction\":180,\"relative_humidity\":90}},"
            + "\"next_6_hours\":{\"summary\":{\"symbol_code\":\"rain\"},\"details\":{\"precipitation_amount\":2.0}}}}"
            + "]}}";

    private static final String SMHI = "{\"timeSeries\":["
            + "{\"time\":\"2026-10-22T04:00:00Z\",\"data\":{\"air_temperature\":9,\"wind_speed\":5,\"wind_direction\":200,\"relative_humidity\":86,\"precipitation_amount_mean\":0.4}},"
            + "{\"time\":\"2026-10-22T11:00:00Z\",\"data\":{\"air_temperature\":13,\"wind_speed\":8,\"wind_direction\":160,\"relative_humidity\":92,\"precipitation_amount_mean\":2.2}}]}";

    private static final String OPEN_METEO = "{"
            + "\"daily\":{\"time\":[\"2026-10-22\",\"2026-10-23\"],\"temperature_2m_max\":[12.3,10],\"temperature_2m_min\":[10.6,7],"
            + "\"precipitation_sum\":[8.3,0],\"windspeed_10m_max\":[8.2,4],\"winddirection_10m_dominant\":[185,90],"
            + "\"apparent_temperature_min\":[9.6,5],\"apparent_temperature_max\":[11.5,9],\"uv_index_max\":[1.2,2]},"
            + "\"hourly\":{\"time\":[\"2026-10-22T01:00\",\"2026-10-22T13:00\"],\"temperature_2m\":[11,12],\"windspeed_10m\":[6,7],"
            + "\"winddirection_10m\":[90,180],\"precipitation\":[0.5,0],\"apparent_temperature\":[10,11]}}";

    private static Weather.Summary today() throws Exception {
        return Weather.summarize(Arrays.asList(
                Weather.parseYr(YR), Weather.parseSmhi(SMHI), Weather.parseOpenMeteo(OPEN_METEO), null));
    }

    @Test
    public void yrDaysFollowUtcDatesLikeNjord() throws Exception {
        Weather.Source yr = Weather.parseYr(YR);
        assertEquals("2026-10-21", yr.days.get(0).date);
        assertEquals("2026-10-22", yr.days.get(1).date);
        assertEquals(Integer.valueOf(8), yr.days.get(1).tempMin);
        assertEquals(Integer.valueOf(13), yr.days.get(1).tempMax);   // 12.5 rounds up, as Math.round
        assertEquals(2.2, yr.days.get(1).precipMm, 1e-9);           // next_1_hours wins over next_6_hours
        assertEquals(Integer.valueOf(89), yr.days.get(1).humidity);
    }

    @Test
    public void openMeteoDayComesFromTheDailyEndpoint() throws Exception {
        Weather.Day d = Weather.parseOpenMeteo(OPEN_METEO).days.get(0);
        assertEquals(Integer.valueOf(11), d.tempMin);
        assertEquals(Integer.valueOf(12), d.tempMax);
        assertEquals(Integer.valueOf(10), d.feelsMin);
        assertEquals(Integer.valueOf(1), d.uv);
        assertEquals(8.3, d.precipMm, 1e-9);
    }

    @Test
    public void summaryMergesDayZeroOfEverySource() throws Exception {
        Weather.Summary s = today();
        // "Today" is Open-Meteo's first day; YR's day 0 is still the 21st, as in Njord.
        assertEquals("2026-10-22", s.date);
        assertEquals(Arrays.asList("YR", "SMHI", "Open-Meteo"), s.sources);
        assertEquals(Integer.valueOf(11), s.tempMin);    // median of 20, 9, 11
        assertEquals(Integer.valueOf(13), s.tempMax);    // median of 20, 13, 12
        assertEquals("5 mm (2.6–8.3)", s.rain);          // YR 5, SMHI 2.6, Open-Meteo 8.3
        assertEquals(Integer.valueOf(1), s.uv);
        assertEquals(Integer.valueOf(70), s.humidity);   // median of 50 and 89, half up
    }

    @Test
    public void periodsMatchOnTheReferenceDate() throws Exception {
        Weather.Summary s = today();
        assertEquals(3, s.periods.size());
        assertEquals(0, s.periods.get(0).slot);
        assertEquals("lightrain", s.periods.get(0).symbol);
        assertEquals("—", Weather.periodRain(0.0));
        assertEquals("2.2 mm", Weather.periodRain(2.2));
    }

    @Test
    public void noSourcesMeansNoSummary() {
        assertNull(Weather.summarize(Collections.emptyList()));
    }

    @Test
    public void rainRangeOnlyWhenTheSourcesDisagree() {
        assertEquals("2 mm", Weather.rainLine(Collections.singletonList(2.0)));
        assertEquals("2.3 mm", Weather.rainLine(Arrays.asList(2.1, 2.3, 2.5)));
        assertEquals("2.3 mm (2.1–8.3)", Weather.rainLine(Arrays.asList(2.1, 2.3, 8.3)));
    }

    @Test
    public void feelsLikeIsWindChillOnlyWhenCold() {
        assertEquals(12.0, Weather.feelsLike(12, 8), 1e-9);
        assertEquals(0.0, Weather.feelsLike(5, 8), 1e-9);
        assertEquals(5.0, Weather.feelsLike(5, 1), 1e-9);
    }

    @Test
    public void agreementCompassAndFormats() {
        assertEquals(Integer.valueOf(100), Weather.agreement(Arrays.asList(10.0, 10.0), Arrays.asList(5.0, 5.0)));
        assertNull(Weather.circularMean(Arrays.asList(0.0, 180.0)));
        assertEquals("E", Weather.compass(90.0));
        assertEquals("SW", Weather.compass(225.0));
        assertEquals("11° / 12°", Weather.temp(11, 12));
        assertEquals("8 m/s S", Weather.wind(8, 180.0));
        assertEquals("—", Weather.wind(null, null));
    }

    @Test
    public void iconsAndGeocoding() throws Exception {
        assertEquals("thunder", Weather.icon("heavyrainandthunder", null));
        assertEquals("rain_light", Weather.icon("lightrain", null));
        assertEquals("partly_night", Weather.icon("partlycloudy_night", null));
        assertEquals("rain", Weather.icon(null, 0.4));
        assertEquals("", Weather.icon(null, 0.0));
        assertArrayEquals(new double[]{57.70528, 11.71}, Weather.parseGeocode(
                "{\"results\":[{\"name\":\"Hjuvik\",\"latitude\":57.70528,\"longitude\":11.71}]}"), 1e-9);
        assertNull(Weather.parseGeocode("{}"));
    }
}
