package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

public class WeatherTest {

    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 22);

    // 21:00Z on the 21st is 23:00 local (CEST): yesterday. 22:00Z is midnight: today's first period.
    private static final String YR = "{\"properties\":{\"timeseries\":["
            + "{\"time\":\"2026-10-21T21:00:00Z\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":20,\"wind_speed\":1,\"wind_from_direction\":0}},"
            + "\"next_1_hours\":{\"summary\":{\"symbol_code\":\"clearsky_night\"},\"details\":{\"precipitation_amount\":5}}}},"
            + "{\"time\":\"2026-10-21T22:00:00Z\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":8,\"wind_speed\":4,\"wind_from_direction\":225}},"
            + "\"next_1_hours\":{\"summary\":{\"symbol_code\":\"cloudy\"},\"details\":{\"precipitation_amount\":0.2}},"
            + "\"next_6_hours\":{\"summary\":{\"symbol_code\":\"lightrain\"},\"details\":{\"precipitation_amount\":1.0}}}},"
            + "{\"time\":\"2026-10-22T10:00:00Z\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":12.4,\"wind_speed\":7,\"wind_from_direction\":270}},"
            + "\"next_6_hours\":{\"summary\":{\"symbol_code\":\"partlycloudy_day\"},\"details\":{\"precipitation_amount\":0.0}}}}"
            + "]}}";

    private static final String SMHI = "{\"timeSeries\":["
            + "{\"time\":\"2026-10-22T04:00:00Z\",\"data\":{\"air_temperature\":9,\"wind_speed\":5,\"wind_direction\":200,\"precipitation_amount_mean\":0.4}},"
            + "{\"time\":\"2026-10-22T11:00:00Z\",\"data\":{\"air_temperature\":13,\"wind_speed\":8,\"wind_direction\":260,\"precipitation_amount_mean\":0}}]}";

    private static final String OPEN_METEO = "{\"hourly\":{\"time\":[\"2026-10-22T01:00\",\"2026-10-22T13:00\",\"2026-10-23T01:00\"],"
            + "\"temperature_2m\":[7.6,11.6,null],\"windspeed_10m\":[3,6,2],\"winddirection_10m\":[210,250,0],\"precipitation\":[0.5,0,null]}}";

    @Test
    public void yrIsGroupedByLocalDateAndHour() throws Exception {
        Weather.Source yr = Weather.parseYr(YR, DAY, STOCKHOLM);
        assertEquals(2, yr.day.temps.size());
        assertEquals("lightrain", yr.periods.get(0).symbol);
        assertEquals(0.2, yr.day.precip, 1e-9);
        assertEquals(Integer.valueOf(12), yr.periods.get(12).tempMax());
    }

    @Test
    public void smhiAndOpenMeteoLandInTheRightPeriods() throws Exception {
        Weather.Source smhi = Weather.parseSmhi(SMHI, DAY, STOCKHOLM);
        assertTrue(smhi.periods.containsKey(6));
        assertTrue(smhi.periods.containsKey(12));
        Weather.Source om = Weather.parseOpenMeteo(OPEN_METEO, DAY, STOCKHOLM);
        assertEquals(2, om.day.temps.size());
    }

    @Test
    public void summaryUsesMediansAndScoresAgreement() throws Exception {
        Weather.Summary s = Weather.summarize(Arrays.asList(
                Weather.parseYr(YR, DAY, STOCKHOLM),
                Weather.parseSmhi(SMHI, DAY, STOCKHOLM),
                Weather.parseOpenMeteo(OPEN_METEO, DAY, STOCKHOLM),
                null), DAY);
        assertEquals(Integer.valueOf(8), s.tempMin);
        assertEquals(Integer.valueOf(12), s.tempMax);
        assertEquals(Arrays.asList("YR", "SMHI", "Open-Meteo"), s.sources);
        assertTrue(s.confidence > 0 && s.confidence <= 100);
        assertEquals(3, s.periods.size());
    }

    @Test
    public void noSourcesMeansNoSummary() {
        assertNull(Weather.summarize(Collections.emptyList(), DAY));
    }

    @Test
    public void agreementAndDirections() {
        assertEquals(Integer.valueOf(100), Weather.agreement(Arrays.asList(10.0, 10.0), Arrays.asList(5.0, 5.0)));
        assertNull(Weather.agreement(Collections.singletonList(10.0), Collections.emptyList()));
        assertNull(Weather.circularMean(Arrays.asList(0.0, 180.0)));
        assertEquals("N", Weather.compass(350.0));
        assertEquals("SV", Weather.compass(225.0));
        assertEquals("00–06", Weather.slotLabel(0));
    }
}
