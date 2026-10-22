# Saga-versionen av launchern

Den här grenen (`saga`) är en egen version av cleanyourtv-launchern för vardagsrummets Philips 55PUS8303
(TPM171E, Android 8.0). `main` hålls som en ren kopia av originalet så att nya versioner därifrån går att
hämta in.

## Vad som skiljer sig från originalet

| Ändring | Var |
|---|---|
| **Väder** under klockan: dagens min/max, vind, nederbörd och de fyra sextimmarsperioderna, sammanvägt från YR, SMHI och Open-Meteo med median och säkerhetsprocent, på samma sätt som [Njord](https://beta.vitail.app/njord) | `Weather.java`, `SagaExtras.java` |
| **Nyheter** i en kolumn till höger som rullar uppåt en rubrik i taget, från SVT Nyheters RSS, med Sveriges Radios trafikmeddelanden insprängda | `News.java`, `SagaExtras.java` |
| **Avgångar** mot stan i sidhuvudet, mittemot klockan: nästa två turer per hållplats, i realtid med försening, via Sleipners API på beta.vitail.app (Västtrafiks nycklar stannar på servern) | `Transit.java`, `SagaExtras.java` |
| **Svensk översättning** av hela launchern: hälsningen, knapparna, menyerna och appbiblioteket | `res/values-sv/strings.xml` |
| **Källknappen som rund ikon**, som Wi-Fi och Inställningar, när `icon_only_buttons` är på | `MainActivity.java` |
| **`INTERNET`-behörighet**, som väder och nyheter behöver. Originalet har ingen nätverksåtkomst | `AndroidManifest.xml` |

Väder och nyheter är avstängda tills temat slår på dem. Utan nycklarna nedan körs ingen tråd och inget
nätverksanrop.

## Temanycklar (bara i den här versionen)

| Nyckel | Exempel | Betydelse |
|---|---|---|
| `weather_name` | `"Hjuvik"` | Orten. Slås upp i Open-Meteos ortssökning precis som Njord gör, så att TV:n och Njord hämtar för samma punkt. Svaret sparas, så uppslagningen görs en gång |
| `weather_lat`, `weather_lon` | `57.70`, `11.72` | Valfritt: fasta koordinater i stället för uppslagning |
| `weather_places` | `[{"name": "Hjuvik", "seconds": 20}, {"name": "Lissabon", "seconds": 10}]` | Flera orter som visas i tur och ordning, var och en sina sekunder. Ersätter `weather_name`; en post kan ha egna `lat`/`lon`. En ort i en annan tidszon visar sin lokala tid i rubriken |
| `transit_stops` | `["Majvik", "Hjuvik"]` | Hållplatserna. Slås upp i Västtrafiks hållplatssökning; första träffen används och sparas |
| `transit_to` | `"Centralstationen"` | Dit turerna ska gå. Med egna Västtrafik-nycklar används den inte: då visas hållplatsens avgångstavla, alla linjer, utom turer vars slutstation är en av `transit_stops` (de är på väg ut, inte in) |
| `transit_away` | `["Lilla Varholmen"]` | Slutstationer som betyder "på väg ut". Turer dit visas inte; hållplatserna i `transit_stops` räknas alltid dit. Annars visas varje linje som går |
| `transit_label` | `"Mot Göteborg"` | Rubriken över avgångarna |
| `buttons_bottom` | `true` | Knapparna (källor, Wi-Fi, appbibliotek, inställningar) i en rad under dockan i stället för uppe till höger; appbibliotekets ruta efter sista raden försvinner. Kräver `library_button: true` för att biblioteket ska finnas kvar |
| `news_rss` | `"https://www.svt.se/nyheter/rss.xml"` | RSS-flöde för nyhetsraden, måste vara https |
| `traffic_area` | `"Göteborg"` | Sveriges Radios trafikområde. Allvarliga trafikmeddelanden (prioritet 1–3, inte planerade vägarbeten, högst sex timmar gamla) rullar med i nyhetskolumnen, märkta TRAFIK. Hämtas var femte minut. Inga meddelanden: ingenting syns |

Vädret hämtas var 30:e minut och nyheterna var 15:e, bara medan hemskärmen syns. Ett misslyckat anrop
behåller det som redan visas och försöker igen efter fem minuter.

## Bygga och installera

```bash
cd ~/cleanyourtv
git fetch mine && git checkout saga && git pull
unset JAVA_HOME
TV_HOST=192.168.1.94 launcher/build-and-install.sh
TV_HOST=192.168.1.94 tools/theme.sh saga/theme
```

`mine` är den här forken (`git remote add mine https://github.com/elmacogbg/cleanyourtv`).

## Västtrafik direkt, utan vitail

Avgångarna går som standard via Sleipner på beta.vitail.app, där Västtrafiks nycklar ligger. Går vitail inte att
nå kan TV:n fråga Västtrafik direkt med egna nycklar från developer.vasttrafik.se (API:t Planera Resa v4):

```bash
cat > saga/vasttrafik.json <<'JSON'
{"client_id": "DIN-CLIENT-ID", "client_secret": "DIN-CLIENT-SECRET"}
JSON
adb push saga/vasttrafik.json /sdcard/Android/data/com.gathin.tvhome/files/vasttrafik.json
rm saga/vasttrafik.json
adb shell am force-stop com.gathin.tvhome && adb shell input keyevent KEYCODE_HOME
```

Launchern flyttar nycklarna till sitt privata lagringsutrymme och raderar filen vid nästa start. `saga/vasttrafik.json`
står i `.gitignore`. Att gå tillbaka till Sleipner: `adb shell pm clear com.gathin.tvhome` (nollställer launcherns
data, temat läggs på igen med `tools/theme.sh saga/theme`).
