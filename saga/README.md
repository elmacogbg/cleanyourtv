# Saga-versionen av launchern

Den här grenen (`saga`) är en egen version av cleanyourtv-launchern för vardagsrummets Philips 55PUS8303
(TPM171E, Android 8.0). `main` hålls som en ren kopia av originalet så att nya versioner därifrån går att
hämta in.

## Vad som skiljer sig från originalet

| Ändring | Var |
|---|---|
| **Väder** under klockan: dagens min/max, vind, nederbörd och de fyra sextimmarsperioderna, sammanvägt från YR, SMHI och Open-Meteo med median och säkerhetsprocent, på samma sätt som [Njord](https://beta.vitail.app/njord) | `Weather.java`, `SagaExtras.java` |
| **Rullande nyheter** längs nederkanten, rubriker från SVT Nyheters RSS | `News.java`, `SagaExtras.java` |
| **Källknappen som rund ikon**, som Wi-Fi och Inställningar, när `icon_only_buttons` är på | `MainActivity.java` |
| **`INTERNET`-behörighet**, som väder och nyheter behöver. Originalet har ingen nätverksåtkomst | `AndroidManifest.xml` |

Väder och nyheter är avstängda tills temat slår på dem. Utan nycklarna nedan körs ingen tråd och inget
nätverksanrop.

## Temanycklar (bara i den här versionen)

| Nyckel | Exempel | Betydelse |
|---|---|---|
| `weather_lat`, `weather_lon` | `57.70`, `11.72` | Platsen för vädret. Båda krävs |
| `weather_name` | `"Hjuvik"` | Namnet ovanför vädret |
| `news_rss` | `"https://www.svt.se/nyheter/rss.xml"` | RSS-flöde för nyhetsraden, måste vara https |

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
