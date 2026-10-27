# Проверка DX • Wrist Sound 1.2.1

9 октября 2026 года. Проверка 1.2.0 сохранена в VERIFICATION-1.2.0.md.

## Изменение

По скриншоту пользователя кнопка настройки наложения на Samsung открывала системный экран «Недоступно», хотя доступ через ADB был выдан и окно работало. Старый PermissionGuide уже проверял Settings.canDrawOverlays; ошибочным был переход в системную Activity независимо от фактического доступа.

Все пути к настройке наложения в Wear UI теперь открывают внутреннюю страницу DX: Разрешения → Окно поверх циферблата → Проверить доступ, Оповещения → Над циферблатом, проверка окна при отсутствии доступа. Страница показывает фактический статус, кнопку перечитывания, проверку окна при выданном доступе и адресованную часам команду ADB. Уже выданное разрешение повторно не запрашивается.

Статус не сохраняется как пользовательский флаг: при каждом обновлении читается Settings.canDrawOverlays. MainActivity наблюдает изменение собственного SYSTEM_ALERT_WINDOW через AppOps между onStart/onStop, перечитывает доступ на onResume и по кнопке. Preview повторно проверяет доступ непосредственно при нажатии. Доступ через ADB приложение само не выдаёт. AccessibilityService не добавлен, поведение предупреждения и ограничение PIN сохранены.

Источники API: [Settings.canDrawOverlays](https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context)), [AppOpsManager.startWatchingMode](https://developer.android.com/reference/android/app/AppOpsManager#startWatchingMode(java.lang.String,java.lang.String,android.app.AppOpsManager.OnOpChangedListener)).

Оба модуля обновлены до versionName=1.2.1, versionCode=5. Протокол обмена не менялся, APK часов совместим с телефоном 1.2.0.

## Проверки

`tools/build-local.ps1` выполнил :mobile:assembleRelease, :wear:assembleRelease, :shared:testDebugUnitTest и lintRelease. **BUILD SUCCESSFUL**, 6 минут 23 секунды; 152 задачи, 56 выполнены, 96 up-to-date. Изменённые исходники в workspace и ASCII build directory совпали по SHA-256.

Все **35 tests** прошли, failures=0 и errors=0: AutomationTest — 10, MonitoringRecoveryTest — 5, RegressionTest — 12, ScreenLockRegressionTest — 8. Новые тесты, повторяющие простые UI ветвления, не добавлялись. Эти тесты не подтверждают системную страницу Wear OS или AppOps callback на устройстве.

Lint: mobile — 0 ошибок / 8 предупреждений, wear — 0 / 4, shared — 0 / 1. При wear lint снова появились два XML parser сообщения `Content is not allowed in prolog`; итоговые отчёты не содержат ошибок. Набор предупреждений соответствует 1.2.0.

`aapt2` подтвердил package dev.dx.wristsound, versionCode=5, versionName=1.2.1, minSdk=30, targetSdk=36 и SYSTEM_ALERT_WINDOW в Wear APK. `apkanalyzer manifest debuggable` вернул false для обоих модулей. Сборка проверила наличие массива android_wear_capabilities и значений dx_wrist_phone / dx_wrist_watch в готовых APK после shrinking.

`apksigner verify --verbose --print-certs` подтвердил v2 подпись обоих APK и прежний сертификат SHA-256:

```text
178153e7125b1a3cd139d0c73fc7ad77a54b36741ae2e26166819b34eee02c24
```

Java CLI не обработал кириллицу в пути E:\Новая папка, поэтому проверка подписи и manifest выполнена для идентичной копии в ASCII build directory. Доставка файлов в workspace проверена SHA-256.

| Файл | Байты | SHA-256 |
|---|---:|---|
| DX-Wrist-Sound-Phone-1.2.1.apk | 2532520 | 6006047CF500A826788A904B873EC3165FB2C7C9638AA4EA9DA290AA04421423 |
| DX-Wrist-Sound-Wear-1.2.1.apk | 2647481 | CEF5BFA5D6640EADCEF6C67BAB1FCD9E3C766C93ABD9BC94C296EC2C2876045A |

APK и Установка-1.2.1.txt сохранены в E:\Новая папка\APK-1.2.1. Переносимый source ZIP обновлён; ключи подписи и машинный local.properties исключены.

## Проверка на часах

При работе над изменением adb devices и adb mdns services не обнаружили подключённых устройств. Новые APK не установлены; реальное отображение новой страницы, callback изменения AppOps и preview окна на часах пока не проверены. Снимок пользователя подтверждает ограничение системной страницы предыдущей версии, но не заменяет проверку новой версии.

После обновления проверить:

1. При уже выданном доступе открыть обе кнопки настройки: появляется страница DX, статус «Разрешено», пример окна показывается.
2. При отсутствии доступа появляется инструкция ADB; проверка окна ведёт на неё, а не в системную Activity.
3. После выдачи или отзыва доступа через ADB обновить статус, не перезапуская приложение; проверить также возврат на страницу из фона.
4. Проверить прокрутку инструкции на круглом экране, возврат к предыдущему разделу и закрытие примера окна.
