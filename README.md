<div align="center">

<img src="docs/screenshots/icon.png" width="128" alt="qThroneMobile">

# qThroneMobile

**Android-клиент на ядрах sing-box и Xray**

Форк [ThroneForAndroid](https://github.com/throneproj/ThroneForAndroid)

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Go](https://img.shields.io/badge/Go-00ADD8?style=for-the-badge&logo=go&logoColor=white)
[![sing-box](https://img.shields.io/badge/sing--box-1F2937?style=for-the-badge)](https://github.com/SagerNet/sing-box)
[![Xray](https://img.shields.io/badge/Xray-2563EB?style=for-the-badge)](https://github.com/XTLS/Xray-core)

[![Downloads](https://img.shields.io/github/downloads/ttrroyy/qThroneMobile/total?label=downloads-total&color=brightgreen)](https://github.com/ttrroyy/qThroneMobile/releases)

</div>

Помимо стандартных протоколов sing-box и Xray поддерживает qWDTT, CSQTT, olcRTC, OpenFlux, DNSTT, VayDNS, SlipStream, MasterDNS, CottenDNS, Soar, S3 Bypass и обфускацию WireGuard (wg-obfuscator, Phobos, wgobfs) - с подписками, тестом пинга и раздельной маршрутизацией.

<p align="center">
  <img src="docs/screenshots/screenshot-subscriptions.png" width="300" alt="Подписки и тест пинга">
  &nbsp;&nbsp;
  <img src="docs/screenshots/screenshot-profiles.png" width="300" alt="Профили разных протоколов">
</p>

## Скачать

Свежий APK - в [Releases](https://github.com/ttrroyy/qThroneMobile/releases/latest). Нужен Android 7.0 или новее; сборки есть для arm64-v8a, armeabi-v7a и x86_64. Приложение само сообщает о новых версиях.

## Особенности

**Новые протоколы.** qWDTT, CSQTT, olcRTC, OpenFlux, DNS-туннели и S3 Bypass запускаются через оригинальные клиенты этих проектов. Работают как обычные профили: с маршрутизацией, цепочками и тестом пинга.

**Привязка аккаунта VK.** Хеши звонков для qWDTT и CSQTT можно получать автоматически.

**Деплой сервера.** Сервер qWDTT или CSQTT можно развернуть на VPS по SSH прямо из приложения и сразу получить ссылку на профиль. Подробнее - в [docs/vk-relay-deployment.md](docs/vk-relay-deployment.md).

## Протоколы

| Тип | Протоколы |
|---|---|
| Стандартные | VLESS, VMess, Trojan, Shadowsocks, SOCKS, HTTP(S), SSH |
| QUIC | Hysteria, Hysteria2, TUIC, MASQUE |
| VPN | WireGuard, AmneziaWG, OpenVPN, OpenConnect, SSTP |
| Другие прокси | ShadowTLS, NaïveProxy, AnyTLS, Mieru, Snell, Juicity, TrustTunnel |
| VK и WebRTC | qWDTT, CSQTT, olcRTC, OpenFlux |
| DNS-туннели | DNSTT, VayDNS, SlipStream, MasterDNS (и StormDNS), CottenDNS, Soar |
| Обфускация WireGuard | wg-obfuscator (маскировка STUN и MEDIA под видеозвонок), Phobos (STUN, MEDIA, TLS, режим SOCKS5), wgobfs |
| S3-хранилище | S3 Bypass |

Также поддерживаются пользовательские outbound, полные конфиги sing-box и Xray, цепочки прокси и дополнительные ядра.

## Как добавить профиль

Кнопка «+» на главном экране: из буфера обмена, по QR-коду, из файла, по ссылке на подписку или вручную. Понимает ссылки всех перечисленных протоколов - в том числе `qwdtt://`, `csqtt://`, `olcrtc://`, `openflux://`, `dnst://`, `slipnet://`, `masterdns://`, `stormdns://`, `cottendns://`, `soar://` и `phobos://`, - а также JSON-конфиги sing-box и Xray (S3 Bypass импортируется из Xray-конфига с outbound `fedarisha`). Обфускатор WireGuard и ключ Phobos задаются и вручную, в редакторе профиля.

## Сборка

APK собирает CI: [`.github/workflows/ci.yml`](.github/workflows/ci.yml), шаги - в [`.github/actions/android-build`](.github/actions/android-build). Нативные клиенты протоколов собираются отдельно ([`carriers.yml`](.github/workflows/carriers.yml)) из закреплённых коммитов оригинальных репозиториев; их архивы и контрольные суммы записаны в [`native-transports.lock.json`](native-transports.lock.json), а `scripts/stage_native_transports.py` скачивает и проверяет их перед сборкой.

## Лицензия

[GPL-3.0](LICENSE). У клиентов сторонних протоколов свои лицензии - они лежат в приложении рядом с бинарниками (`assets/carrier-licenses`).

## Благодарности

- Ядра: [SagerNet/sing-box](https://github.com/SagerNet/sing-box), [XTLS/Xray-core](https://github.com/XTLS/Xray-core), [ThroneCore](https://github.com/ttrroyy/qThrone)
- Основа клиента: [ThroneForAndroid](https://github.com/throneproj/ThroneForAndroid), [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid), [SagerNet](https://github.com/SagerNet/SagerNet)
- VK и WebRTC: [proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android), [amurcanov/csqtt](https://github.com/amurcanov/csqtt), [olcRTC](https://github.com/openlibrecommunity/olcrtc), [OpenFlux](https://github.com/p1neappleXpress/OpenFlux)
- DNS-туннели: [dnstt](https://www.bamsoftware.com/software/dnstt/), [VayDNS](https://github.com/net2share/vaydns), [slipstream-rust](https://github.com/Mygod/slipstream-rust), [MasterDnsVPN](https://github.com/masterking32/MasterDnsVPN), [CottenDNS](https://github.com/TaJirax/cottenDNS), [Soar](https://github.com/getlantern/soar), [dnst-url-spec](https://github.com/net2share/dnst-url-spec), [SlipNet](https://github.com/anonvector/SlipNet), [WhiteDNS](https://github.com/WhiteDNS/WhiteDNS-Android)
- S3 Bypass и TrustTunnel: [s3-bypass](https://github.com/SpaceNeuroX/s3-bypass), [Xray-core-fedarisha](https://github.com/Fedarisha/Xray-core-fedarisha), [sing-trusttunnel](https://github.com/xchacha20-poly1305/sing-trusttunnel), [TrustTunnelClient](https://github.com/TrustTunnel/TrustTunnelClient)
- SSTP: [SwiftSSTP](https://github.com/alichdrdzn/swiftsstp)
- Обфускация WireGuard: [wg-obfuscator](https://github.com/ClusterM/wg-obfuscator), [Phobos](https://github.com/Ground-Zerro/Phobos), [xt_wgobfs](https://github.com/infinet/xt_wgobfs), [rs-wgobfs](https://github.com/infinet/rs-wgobfs)
- Прочее: [routeprofiles](https://github.com/throneproj/routeprofiles), [sing-box-dashboard](https://github.com/SagerNet/sing-box-dashboard), [flag-icons](https://github.com/lipis/flag-icons)

## Обсуждение и поддержка

[Группа в Telegram](https://t.me/raw_comm)

Понравилось - ставь ⭐ репозиторию: это помогает другим найти проект.

Поддержать разработку монеткой:

| Сеть | Адрес |
|---|---|
| <img src="https://img.shields.io/badge/USDT-TRC--20-26A17B?style=flat-square&logo=tether&logoColor=white" alt="USDT TRC-20"> | `TL22P1n3JmVkhYU8RyMXfRUURx4PN6h4WZ` |
| <img src="https://img.shields.io/badge/USDT%20%2F%20BNB-BEP--20-F0B90B?style=flat-square&logo=bnbchain&logoColor=white" alt="USDT / BNB BEP-20"> | `0x0B7e9f024e92f7333AB9146c0351732B959D4e9A` |
| <img src="https://img.shields.io/badge/BTC-Bitcoin-F7931A?style=flat-square&logo=bitcoin&logoColor=white" alt="Bitcoin"> | `bc1qw5larutup50tdk4vuskdzep5r9ydyzt4g0c6dm` |
| <img src="https://img.shields.io/badge/TON-Toncoin-0098EA?style=flat-square&logo=ton&logoColor=white" alt="Toncoin"> | `UQBFy4FDUovKADmWYNumVFsfgKNvI3uuzPy34fHd3sEI1R86` |
