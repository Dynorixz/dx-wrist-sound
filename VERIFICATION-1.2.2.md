# Проверка DX • Wrist Sound 1.2.2

9 октября 2026 года. Проверка 1.2.1 сохранена в VERIFICATION-1.2.1.md.

## Оформление окна

Окно предупреждения над циферблатом переработано по предоставленному пользователем изображению: фотография телефона на столе, красная рамка, белая надпись «ВЫ ОСТАВИЛИ», жёлтая «ТЕЛЕФОН!», знак предупреждения и золотистая кнопка «Понятно».

Photo — локальный PNG drawable-nodpi, созданный встроенным imagegen. Файл и полный промпт описаны в docs/phone-loss-artwork.md. Текст и кнопка остаются нативными View. PhoneLossAlertView учитывает короткую сторону дисплея и безопасные ширины для круглого экрана; ScrollView позволяет добраться до действия при большом системном шрифте. Кнопка минимум 48 dp. Bitmap декодируется на IO с inSampleSize, перед показом проверяется permission и revision. Подготовка отменяется на hide/remove; invokeOnCompletion освобождает CPU lease, ограниченный 10 секундами. Декодирование не выполняется на каждом кадре.

Подтверждение потери связи, проверка доверенного локального узла, голос/мелодия и восстановление мониторинга не менялись. Таймаут окна остаётся 30 секунд после успешного addView, reconnect/stop и кнопка закрывают его. Доступ SYSTEM_ALERT_WINDOW и ограничение PIN прежние.

## Макет

Browser preview artifacts/qa-1.2.2/preview.html проверен в Codex in-app browser. Снимок artifacts/qa-1.2.2/popup-preview.png показывает композицию на кругах 400 и 320 px: заголовки читаются, рамка и кнопка помещаются. Это макет, не реальный Android screenshot: системный condensed шрифт, SP-масштаб и фиксированный минимум 48 dp кнопки могут отличаться. Фактическое оформление, увеличенный шрифт и поведение окна на часах ещё требуют аппаратной проверки.

## Сборка

Полная сборка :mobile:assembleRelease :wear:assembleRelease :shared:testDebugUnitTest lintRelease завершилась BUILD SUCCESSFUL за 5 минут 5 секунд. 35 unit tests имели failures=0 / errors=0; shared исходники не менялись, тестовая задача использовала актуальные результаты UP-TO-DATE. Mobile lint: 0 ошибок / 8 предупреждений; Wear lint: 0 / 4; Shared lint: 0 / 1. Два сообщения XML parser «Content is not allowed in prolog» появились и в этой сборке; итоговые отчёты не содержат ошибок.

После добавления краткого CPU lease на передачу подготовленного изображения в окно завершающая сборка :wear:assembleRelease :wear:lintRelease прошла **BUILD SUCCESSFUL за 2 минуты 31 секунду**. 85 задач: 10 выполнены, 75 up-to-date. Wear lint: 0 ошибок / 4 предупреждения (EmptySuperCall, DataExtractionRules, ViewConstructor для программно создаваемого View, UseKtx). Код Popup в workspace и build directory совпал по SHA-256.

Проверка packaged capabilities прошла: mobile / dx_wrist_phone в полной сборке и wear / dx_wrist_watch в обеих. `aapt2 dump resources` подтвердил включение phone_left_background и phone_left_warning. `aapt2 dump badging` подтвердил package dev.dx.wristsound, versionName=1.2.2, versionCode=6, targetSdk=36 и SYSTEM_ALERT_WINDOW. `apkanalyzer manifest debuggable` вернул false для обоих APK.

`apksigner verify --verbose --print-certs` подтвердил v2 обоих APK и совпадение прежнего сертификата SHA-256:

```text
178153e7125b1a3cd139d0c73fc7ad77a54b36741ae2e26166819b34eee02c24
```

| Файл | Байты | SHA-256 |
|---|---:|---|
| DX-Wrist-Sound-Phone-1.2.2.apk | 2532520 | E50ADED3A416352FE2165E5605E459B286EBB6BD639F16867B03AAF4B2C9A417 |
| DX-Wrist-Sound-Wear-1.2.2.apk | 4218665 | 9472075059FEA41EB8332F5C3C7A5E0F70903498128C78C1D94507918D4F0928 |

Файлы и Установка-1.2.2.txt сохранены в E:\Новая папка\APK-1.2.2. Переносимый source ZIP обновлён; новая фотография, нативный View и промпт включены, ключи подписи и local.properties исключены. Увеличение Wear APK обусловлено локальной фотографией.

## Устройства

ADB устройства и mDNS записи отсутствовали. Новые APK не установлены. На часах проверить через Оповещения → Проверить окно, затем реальный disconnect, reconnect, кнопку «Понятно», таймаут и крупный шрифт. Системные разрешения не выдавались и дополнительные permission не добавлялись.
