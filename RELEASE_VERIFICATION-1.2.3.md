# Релиз и подпись DX • Wrist Sound 1.2.3

11 октября 2026 года. Функциональные исправления и ограничения проверки описаны отдельно в BUILD_VERIFICATION.md. Код приложения в этой сборке не менялся.

## Сборка

tools/build-local.ps1 завершился BUILD SUCCESSFUL за 32 секунды: 181 задача, 3 выполнены, 178 up-to-date. Собраны mobile:assembleRelease и wear:assembleRelease, выполнен lintRelease, задачи shared:testDebugUnitTest / wear:testDebugUnitTest использовали актуальные результаты UP-TO-DATE. В отчётах 49 тестов, failures=0 / errors=0. Lint: Mobile 0 ошибок / 8 предупреждений, Wear 0 / 4, Shared 0 / 1. Проверка packaged capabilities прошла для обоих модулей.

tools/sign-release.ps1 -SkipBuild подписал готовые release APK отдельным PKCS12-ключом. Ключ создан один раз и повторно использован при завершении подписи. Защищённый пароль хранится через DPAPI; закрытый каталог имеет три правила доступа (текущий пользователь, SYSTEM, администраторы), наследование отключено. Ключ/пароль не находятся в проекте или папках APK.

## Проверки подписанных APK

- apksigner verify --verbose --print-certs --min-sdk-version 24: обоим APK подтверждены v2=true / v3=true, один signer.
- Обычный apksigner verify также проходит для фактического minSdk=30; в обычном диапазоне он проверяет v3 и может показывать v2=false, поскольку нижняя граница Android 11 не требует проверки v2.
- Сертификат обоих APK: CN=DX Wrist Sound, O=dynorixz, RSA 3072 bit, SHA256withRSA, действителен до 26 февраля 2054 года. Это отдельный релизный сертификат, отличающийся от Android Debug.
- apkanalyzer manifest debuggable=false; aapt2 package=dev.dx.wristsound, versionName=1.2.3, versionCode=7, minSdk=30.
- zipalign -c -P 16 4 прошёл до и после подписи; после подписи APK не изменялся.
- android_wear_capabilities и dx_wrist_phone / dx_wrist_watch присутствуют в подписанных APK.
- ZIP CRC корректны; SHA-256 всех записей вне META-INF совпали с исходными Gradle APK: код, manifest, ресурсы и asset не изменены подписью.
- Совместимые APK побайтово совпали с исходными Gradle APK. Их сертификат совпал с предыдущими локальными версиями.

Релизный certificate SHA-256:

```text
d3c881d7e1a1a95a4570d6a194b5aa661614e8fbc1e95d001a5659396aea7618
```

Совместимый certificate SHA-256:

```text
178153e7125b1a3cd139d0c73fc7ad77a54b36741ae2e26166819b34eee02c24
```

| APK | Байты | SHA-256 |
|---|---:|---|
| DX-Wrist-Sound-Phone-1.2.3-release.apk | 2528900 | 8C7FCFE0E3A9947E2EC5185172CDC906DCFA67CBA75D43AC4DCE70A91F6B37C5 |
| DX-Wrist-Sound-Wear-1.2.3-release.apk | 4233293 | E03E5FCBCA60A1619E4E30BF8903E6F4875EE9138C9C93AA6FC28B285EFDA809 |
| DX-Wrist-Sound-Phone-1.2.3-compatible.apk | 2532524 | D25D7A109AA473489A54397588C29FBA9AB6899B757EBC22233002BCE12D1909 |
| DX-Wrist-Sound-Wear-1.2.3-compatible.apk | 4235049 | FBD7E6A71E36B885446DB3740C6DF91717755CED6ACBC5F42D735F3F316930D7 |

Релизные файлы: E:\Новая папка\APK-1.2.3-Release. Совместимые: E:\Новая папка\APK-1.2.3-Compatible. Открытый сертификат и полные отчёты подписи лежат рядом с релизными APK. Private key: %USERPROFILE%\.codex\signing\DX-Wrist-Sound\dx-wrist-release.keystore; пароль защищён в release-password.dpapi. Исходный ZIP обновлён без закрытых ключей и DPAPI-файлов.

Установка, удаление старых приложений, изменение системных разрешений и публикация в этой задаче не выполнялись. Релизные APK с новым сертификатом не устанавливаются поверх прежних debug-версий. Для сохранения существующих данных при обновлении доступны APK с прежней подписью. Ротация сертификата/signing lineage не настраивались. Используйте одну пару на телефоне и часах. [Требования Android к подписи обновления](https://developer.android.com/studio/publish/app-signing), [apksigner](https://developer.android.com/tools/apksigner).
