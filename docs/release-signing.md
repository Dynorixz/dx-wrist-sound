# Подпись релиза DX • Wrist Sound

С 11 октября 2026 года отдельные релизные APK подписываются ключом `dx-wrist-release`. Ключ создан для 1.2.3 и повторно используется для 1.2.4 (versionCode=8). Телефон и часы используют один сертификат, package dev.dx.wristsound и разные ресурсы capabilities. После подписи проверяются обе схемы v2/v3, manifest debuggable=false, версия, package, capabilities и zipalign.

## Повторная сборка

В PowerShell из корня проекта:

```powershell
./tools/sign-release.ps1
```

Скрипт сначала вызывает build-local.ps1 для release-сборки, тестов и lint, затем подписывает оба APK отдельным релизным ключом. Если build-local.ps1 уже успешно выполнен для актуального исходного кода:

```powershell
./tools/sign-release.ps1 -SkipBuild
```

Gradle release по-прежнему даёт промежуточные сборки с прежним локальным debug-сертификатом. Итоговые APK с производственным сертификатом создаёт именно sign-release.ps1. Ключ создаётся один раз; при последующих запусках используется тот же. Если существующий ключ потерял файл защищённого пароля, скрипт останавливается и не заменяет ключ другим.

Итоговые файлы находятся рядом с проектом в `APK-<версия>-Release`; локальные сборки с прежней подписью — в `APK-<версия>-Compatible`. Папка Release содержит два APK, открытый сертификат RELEASE-CERTIFICATE.pem, отчёты signature-mobile.txt / signature-wear.txt и release-info.json. Закрытый ключ и пароль туда не копируются.

## Сертификаты и обновления

Релизный сертификат SHA-256:

```text
d3c881d7e1a1a95a4570d6a194b5aa661614e8fbc1e95d001a5659396aea7618
```

CN=DX Wrist Sound, O=dynorixz; RSA 3072 bit; SHA256withRSA; срок действия до 26 февраля 2054 года.

Предыдущий локальный debug-сертификат SHA-256:

```text
178153e7125b1a3cd139d0c73fc7ad77a54b36741ae2e26166819b34eee02c24
```

APK из Release подходят для новой установки или последующих обновлений приложения, подписанного новым релизным ключом. Они не устанавливаются поверх прежних debug-версий: сертификаты различаются, signing lineage/ротация ключа не настраивались. Для обновления существующих локальных установок с сохранением данных используйте APK из Compatible. При переходе на новую подпись требуется переустановка обоих приложений; локальные настройки при удалении могут быть потеряны. Удаление, установка и выдача системных разрешений этой сборкой не выполнялись. [Требования Android к ключу обновления](https://developer.android.com/studio/publish/app-signing).

Оба устройства должны использовать APK одной пары: Release + Release или Compatible + Compatible. У локальной связи Wear OS проверяется package и подпись приложения; смешивание этих пар меняет сертификат одного из участников.

## Хранение ключа

Закрытый ключ находится вне репозитория и исходного ZIP:

```text
%USERPROFILE%\.codex\signing\DX-Wrist-Sound\dx-wrist-release.keystore
```

Alias: `dx-wrist-release`. Keystore имеет формат PKCS12. Случайный пароль сохраняется рядом в release-password.dpapi, зашифрованный Windows DPAPI через ConvertFrom-SecureString; сам пароль не записывается в исходный код, журнал, аргументы процесса или APK. Во время подписи он передаётся keytool/apksigner через временную переменную среды и очищается в finally. Каталог закрыт наследуемыми правами: доступ только у текущего пользователя, SYSTEM и администраторов. [DPAPI в PowerShell](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.security/convertfrom-securestring).

Сохраните резервную копию keystore и пароль в своём защищённом хранилище. Файл DPAPI предназначен для текущего профиля Windows; для переноса ключа пароль нужно извлечь в этом профиле и сохранить отдельно. Для ручного извлечения пароля перед переносом, в PowerShell под тем же пользователем:

```powershell
$passwordPath = Join-Path $env:USERPROFILE '.codex\signing\DX-Wrist-Sound\release-password.dpapi'
$protected = Get-Content -LiteralPath $passwordPath -Raw | ConvertTo-SecureString
[System.Management.Automation.PSCredential]::new('dx-wrist-release', $protected).GetNetworkCredential().Password
```

Эта команда показывает пароль только при ручном запуске пользователем; при подготовке релиза она не выполнялась. Не включайте keystore и пароль в исходный ZIP или отправляемые APK. Source packager и .gitignore исключают keystore/jks/dpapi/p12/pfx.

APK уже выровнен Gradle до подписи; финальное выравнивание проверяется без изменения файла. После подписи APK не редактируется. Для отдельной проверки двух блоков подписи используется `apksigner verify --verbose --print-certs --min-sdk-version 24`: расширенный диапазон проверки заставляет проверить также v2; minSdk приложения остаётся 30. Проверка с обычным диапазоном также проходит. [Документация apksigner](https://developer.android.com/tools/apksigner).
