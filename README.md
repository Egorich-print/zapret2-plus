# Zapret2 Plus

Форк [magisk-zapret2](https://ywumezrlvv.zapret.moe/zapretdiscordyoutube/magisk-zapret2) (автор
bol-van, лицензия AGPL-3.0): приложение управления модулем zapret2 **плюс встроенный
VPN-клиент** и **автоматическое переключение Wi-Fi ⇄ мобильный интернет**.

Дизайн интерфейса, модуль, пресеты, хостлисты, DNS-менеджер и журналы — всё как в оригинале.
Добавлен отдельный экран «VPN» и сервис управления сетью.

## Что добавлено

| Возможность | Где |
| --- | --- |
| VPN на ядре sing-box 1.14.4 (VLESS/VMess/Trojan/SS/Hysteria2/TUIC) | `android-app/app/src/main/java/com/zapret2/app/vpn/` |
| Подписки: URL, base64 или обычный список ссылок, обновление по кнопке | `vpn/ShareLinkParser.kt`, `vpn/VpnRepository.kt` |
| Тест серверов: TCP-замер задержки до каждого сервера | `vpn/VpnRepository.testServers()` |
| Переключение Wi-Fi ⇄ LTE и авто-возврат на Wi-Fi | `network/NetworkPolicy.kt`, `network/NetworkGuardService.kt` |
| Режимы маршрутизации: Авто / Только Wi-Fi / Только мобильный интернет | экран «VPN» → «Маршрутизация» |

Приложение ставится рядом с оригиналом: `applicationId` форка — `com.zapret2.plus`,
внутренний пакет кода остался `com.zapret2.app`, поэтому правки в Kotlin-исходниках минимальны.

## Как это работает с zapret2

Модуль zapret2 применяет свои правила ко всем интерфейсам сразу (`wifi_only=1` в модуле
намеренно блокирует старт: «нет проверенного селектора интерфейса»). Поэтому выбор интерфейса
сделан на уровне сети, а не правил:

* **Только Wi-Fi / Только LTE** — приложение переключает радиомодули через root
  (`svc wifi enable|disable`, `svc data enable|disable`);
* **Авто** — при потере интернета на Wi-Fi устройство уходит на мобильный интернет, затем
  периодически проверяет Wi-Fi и возвращается на него, как только он заработает;
* туннель sing-box привязывается к выбранной сети (`VpnService.setUnderlyingNetworks` на
  Android 13+), а при смене сети ядру отправляется `resetNetwork()`.

## Сборка

```sh
# ядро sing-box (81 МБ, качать придётся один раз)
sh tools/fetch-libbox.sh 1.14.4

# сборка
export JAVA_HOME=/path/to/jdk-21
cd android-app
./gradlew :app:assembleRelease
```

Подпись релиза берётся из `android-app/keystore.jks` и переменных окружения:

```sh
export KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=...
```

Собранный APK: `android-app/app/build/outputs/apk/release/app-release.apk`.

Проверки проекта (юнит-тесты и lint) — те же, что в оригинале:

```sh
cd android-app && ./gradlew :app:testDebugUnitTest :app:lintDebug
```

## Лицензия

Код распространяется на условиях **AGPL-3.0-only**, как и исходный проект. Ядро sing-box
внутри — GPL-3.0 (SagerNet), исходники доступны по адресу
<https://github.com/SagerNet/sing-box>.

Правки в `android-app/app/src/test/` — это политики проекта, а не тесты ради тестов:
при добавлении нового экрана их списки обновляются явно.
