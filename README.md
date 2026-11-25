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

## Установка

Готовый APK: [релиз v2.3.11-plus](https://github.com/Egorich-print/zapret2-plus/releases/tag/v2.3.11-plus)
(`zapret2-plus-2.3.11.apk`, arm64-v8a, подписан ключом `zapret2plus`).

1. Поставить APK — он ставится **рядом с оригиналом** (`com.zapret2.plus` ≠ `com.zapret2.app`),
   но Magisk-модуль `zapret2` должен быть установлен отдельно, иначе экран Control не найдёт модуль.
2. Нужен root: приложение переключает радиомодули и управляет модулем через `su`.
3. Экран «VPN» → вставить URL подписки → «Update» → выбрать сервер → «Connect» → в
   системном диалоге подтвердить подключение VPN.

Подписка принимается в трёх видах: обычный список ссылок, base64-список (как у большинства
панелей) и ссылка на любой из них. Поддерживаются VLESS (в т.ч. Reality), VMess, Trojan,
Shadowsocks, Hysteria2, TUIC.

> В релизной сборке отключён cleartext-трафик: подписка должна отдаваться по **https**.
> http-подписки работают только в debug-сборке.

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
