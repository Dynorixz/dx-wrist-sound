# Проверка DX • Wrist Sound 1.2.3

9 октября 2026 года. Проверка 1.2.2 сохранена в VERIFICATION-1.2.2.md.

## Исправление звука часов

Основание — пользовательский screenshot главной страницы часов с ошибкой «Not allowed to change Do Not Disturb state». В прежнем WatchRepository снимок звука записывался до попытки переключения даже при отказе; restoreSound не проверял доступ для переходов через SILENT и передавал текст исключения непосредственно в UI.

WatchSoundController вынесен отдельно с интерфейсами устройства и хранилища. Переходы через SILENT проверяют Notification Policy Access до новой записи снимка. Снимок сохраняется до системного вызова для восстановления после прерывания процесса. При отказе и неизменившемся состоянии новое владение очищается; если что-то изменилось, снимок остаётся. Restore распознаёт уже восстановленное состояние, задаёт громкость перед окончательным режимом, проверяет результат и сохраняет незавершённое восстановление для повтора. Ранее оставленная неизменившаяся запись очищается.

Ошибка звука передаётся в WatchUiState отдельным WatchSoundProblem. В главном меню показана русская подсказка и кнопка «Доступ к звуку» или системных настроек звука. Ошибка не прерывает confirmed/publish и не подменяет Data Layer. На странице доступа показан isNotificationPolicyAccessGranted, системный переход, повторная проверка и ручная ADB-инструкция. Разрешение окна над циферблатом объясняется отдельно.

Возвращение в Activity и системный ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED повторяют обработку звука. Activity обновляет статус при изменении доступа, пока видима; служба обрабатывает событие в фоне. При запуске службы UI refresh не восстанавливает звук до begin и подтверждения датчика. Постоянный опрос разрешений и AccessibilityService не добавлены. Манифестные разрешения прежние; ACCESS_NOTIFICATION_POLICY уже присутствовал. Системные доступы через ADB не выдавались.

## Тесты и сборка

Полная сборка tools/build-local.ps1 (:mobile:assembleRelease :wear:assembleRelease :shared:testDebugUnitTest :wear:testDebugUnitTest lintRelease) завершилась BUILD SUCCESSFUL за 5 минут 53 секунды: 181 задача, 75 выполнены, 106 up-to-date. Shared: 35 тестов, failures=0 / errors=0; Wear WatchSoundControllerTest: 14 тестов, failures=0 / errors=0. Все 49 тестов прошли. Mobile lint: 0 ошибок / 8 предупреждений; Shared: 0 / 1; Wear: 0 / 4.

После завершающего изменения экрана разрешений (:wear:assembleRelease :wear:testDebugUnitTest :wear:lintRelease) сборка прошла BUILD SUCCESSFUL за 2 минуты 44 секунды: 124 задачи, 16 выполнены, 108 up-to-date. Все 14 тестов часов снова прошли; Wear lint: 0 ошибок / 4 предупреждения. Два сообщения XML parser «Content is not allowed in prolog» появляются при Wear lint и в предыдущих версиях; итоговый отчёт не содержит ошибок. Предупреждения Wear: EmptySuperCall, DataExtractionRules, ViewConstructor, UseKtx.

Тесты проверяют отказ перед изменением звука, OEM SecurityException из NORMAL, отсутствие ложного владения при no-op, частичное изменение режима и громкости, восстановление после создания нового контроллера, уже пользовательский VIBRATE, ожидание разрешения при восстановлении SILENT, очистку старых записей без системных вызовов, порядок volume/mode, повтор после отказа и fixed-volume. Это JVM-тесты с имитацией AudioManager и хранилища, не аппаратная проверка Samsung.

SHA-256 MainActivity, WatchRepository, WatchSoundController, WatchViewModel, WristMonitorService и теста совпали между workspace и ASCII build directory. Проверка packaged capabilities в build-local.ps1 прошла: mobile / dx_wrist_phone и wear / dx_wrist_watch. aapt2 подтвердил package dev.dx.wristsound, versionName=1.2.3, versionCode=7, minSdk=30. apkanalyzer manifest debuggable вернул false для обоих APK. Протокол совместим с 1.2.0–1.2.2; оформление предупреждения из 1.2.2 сохранено.

apksigner verify --verbose --print-certs подтвердил APK Signature Scheme v2 и совпадение прежнего сертификата SHA-256:

```text
178153e7125b1a3cd139d0c73fc7ad77a54b36741ae2e26166819b34eee02c24
```

| Файл | Байты | SHA-256 |
|---|---:|---|
| DX-Wrist-Sound-Phone-1.2.3.apk | 2532524 | D25D7A109AA473489A54397588C29FBA9AB6899B757EBC22233002BCE12D1909 |
| DX-Wrist-Sound-Wear-1.2.3.apk | 4235049 | FBD7E6A71E36B885446DB3740C6DF91717755CED6ACBC5F42D735F3F316930D7 |

Файлы и Установка-1.2.3.txt сохранены в E:\Новая папка\APK-1.2.3. Source ZIP обновляется tools/package-source.py без build, artifacts, local.properties и ключей подписи.

## Устройства и предел проверки

Samsung SM_L355F отображался offline. Попытки подключения к 192.168.31.248:41293 и новому порту mDNS 192.168.31.248:43885 завершились failed to connect. Версию установленного приложения, текущий звук и policy access прочитать не удалось. Телефон не был доступен в ADB. APK 1.2.3 на устройства в этой сессии не установлены.

На часах требуется аппаратно проверить: отказ без policy access показывает русскую подсказку, состояние ношения продолжает отправляться; экран «Доступ к звуку» показывает фактический статус; после ручной выдачи доступа переключение/восстановление повторяется; при снятии под PIN команда на телефон доставляется; настройки сохраняются при обновлении; оформление окна потери телефона сохранено. Проверки с PIN и ночью из предыдущих исправлений не повторялись в этой сессии.

Ссылки на правило Android и ADB-команду приведены в README.md. Исправление обработки отказа не отменяет требование Android выдать Notification Policy Access для операций, затрагивающих DND.