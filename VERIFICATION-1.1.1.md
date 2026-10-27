# Проверка DX • Wrist Sound 1.1.1

Проверки выполнены в этом сеансе 4 октября 2026 года.

## Сборка

Выполнено `tools/build-local.ps1`, эквивалент команд:

```text
gradlew assembleDebug :shared:testDebugUnitTest lintDebug
```

Последняя сборка: **BUILD SUCCESSFUL**, 1 минута 23 секунды.
Собраны оба APK с versionCode 3 и versionName 1.1.1.
Package: `dev.dx.wristsound`, minSdk 30, targetSdk 36, compileSdk 37.2.

## Тесты и Lint

**30 тестов**, failures **0**, errors **0**: 10 базовых, 12 регрессионных и 8 для CPU lease / debounce / доставки.
Регрессионные проверки покрывают загрузку без телефона, краткий disconnect,
гейт ON_WRIST, неизменность таймаута при повторных событиях,
отключение тревоги, подавление повторной речи при флаппинге,
минутное взведение после reconnect, оба формата настроек,
задержанный heartbeat с единственным probe-окном,
ответ UNKNOWN и свежий ON_WRIST, отбрасывание четырёхсекундной ошибки off-body.

Новые 8 проверок: удержание CPU от callback через debounce и асинхронную отправку;
снятие/надевание до подтверждения; duplicate raw callback без продления задержки;
завершение старой отправки при новом событии; остановка во время debounce;
ошибка доставки; cleanup старой подписки при запуске новой;
UNKNOWN после начального UNKNOWN без утечки удержания CPU.
Это тесты контракта владения, а не эмуляция аппаратного Keyguard.

Отчёт: `shared/build/reports/tests/testDebugUnitTest/index.html`.

| Модуль | Ошибки Lint | Предупреждения |
|---|---:|---:|
| mobile | 0 | 7 |
| wear | 0 | 1 |
| shared | 0 | 0 |

Предупреждения относятся к targetSdk 36 при compileSdk 37.2,
наличию более новых Gradle/Compose Compiler/coroutines и применимости
dataExtractionRules только на Android 12+. Backup также отключён
через allowBackup=false. Пара Kotlin/Compose Compiler совместима со встроенным
Kotlin Android Gradle Plugin. Настройка android.overridePathCheck экспериментальна;
локальная сборка запускается из временной копии с ASCII-путём.

## Подписи и ссылки

`apksigner verify --verbose --print-certs` подтвердил подпись v2 обоих APK.
Сертификат совпадает между устройствами и с предыдущей debug-сборкой.

SHA-256 сертификата:

```text
178153e7125b1a3cd139d0c73fc7ad77a54b36741ae2e26166819b34eee02c24
```

`aapt2 dump badging` подтвердил versionCode/versionName/package/minSdk/targetSdk.
Проверка badging APK часов также подтвердила `android.permission.WAKE_LOCK`.
Авторские ресурсы с тремя ссылками сохранены с предыдущей версии в обоих модулях:

- https://t.me/dynorixz
- https://github.com/dynorixz
- https://pay.cloudtips.ru/p/5d56061d

| APK | Размер в байтах |
|---|---:|
| mobile/build/outputs/apk/debug/mobile-debug.apk | 33888262 |
| wear/build/outputs/apk/debug/wear-debug.apk | 39159865 |

SHA-256 файлов:

```text
mobile-debug.apk
0517F17E1D105E09B5B8080FDE91EB7AD99E50CA500402765F4488CC86397414

wear-debug.apk
85A8A8C31C6D7BCFD7556D4A1BBB63EEA976EE333A0B027F5C72F19240536004
```

Оба APK имеют прежний сертификат и увеличенный versionCode; поддерживается обновление поверх 1.0.0 и 1.1.0.
Для публикации release-сборок используйте один собственный signing key.

## Установка на телефон

На телефоне Xiaomi (ro.product.model=25113PN0EG) выполнен `adb install -r`: **Success**.
`dumpsys package` подтвердил versionCode=3, versionName=1.1.1;
`am start -W` вернул Status: ok, приложение запущено.
Оба APK с понятными названиями скопированы в `/sdcard/Projects` и сверены по SHA-256.
Копии для передачи: `E:\Новая папка\APK-1.1.1`.

## Что не проверено аппаратно

Часы по ADB недоступны. Установка нового Wear APK и реальное снятие при включённом PIN пока не подтверждены.
Пользователь сообщил модели Xiaomi 17 и Samsung Watch 9;
на физической паре ещё не проверены sensor callbacks,
воспроизведение русского голоса, фактический heads-up баннер,
реальные звонки, HyperOS/Wear OS Doze, разрешения и reboot.

Автоматические тесты подтверждают поведение политики и debounce,
а не полное устранение всех ошибок прошивки или конкретных аппаратных датчиков.
Установка, настройка речи/уведомлений и сценарии проверки описаны в README.md.
