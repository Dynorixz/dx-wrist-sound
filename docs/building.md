# Сборка из исходников

[← На главную](../README.md) · [Архитектура](../ARCHITECTURE.md)

## Инструменты

| Компонент | Версия проекта |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 9.6.0, SHA-256 закреплён |
| Android Gradle Plugin | 9.4.1 |
| Kotlin / Compose Compiler | 2.2.10 |
| Compile SDK | 37.2 |
| Target / min SDK | 36 / 30 |
| SDK Build Tools для проверки подписи | 36.0.0 |

Все версии зависимостей — в [libs.versions.toml](../gradle/libs.versions.toml). Библиотеки скачиваются Gradle из объявленных репозиториев; SDK и кэши в исходный ZIP не включаются.

## Android Studio

Распакуйте ZIP или клонируйте репозиторий и откройте каталог с `settings.gradle.kts`. Установите указанные компоненты SDK, выберите JDK 17 и дождитесь синхронизации. Android Studio создаст локальный `local.properties`; в репозитории его нет.

## Командная строка

Укажите `JAVA_HOME` на JDK 17 и SDK через Android Studio / `local.properties`. На Windows используйте `gradlew.bat`, на Linux/macOS — `./gradlew`.

```bash
./gradlew :shared:testDebugUnitTest :wear:testDebugUnitTest lintRelease
./gradlew :mobile:assembleDebug :wear:assembleDebug
./gradlew :mobile:assembleRelease :wear:assembleRelease
```

На Windows в PowerShell:

```powershell
.\gradlew.bat :shared:testDebugUnitTest :wear:testDebugUnitTest lintRelease
.\gradlew.bat :mobile:assembleRelease :wear:assembleRelease
```

Если ZIP распакован на Unix без исполняемого бита, выполните `chmod +x gradlew`.

## Подпись

Gradle release сейчас подписывается локальным debug-ключом среды сборки. Это удобно для разработки, но **не воспроизводит** ни официальный Release-сертификат, ни прежний Compatible-сертификат автора на другой машине.

Для выпуска собственной пары задайте свой signing config или используйте Windows-скрипт [sign-release.ps1](../tools/sign-release.ps1). Он хранит свой ключ вне проекта и после сборки проверяет оба APK. Официальный закрытый ключ в репозитории и ZIP отсутствует. [Подробнее о подписи](release-signing.md).

Для локальной сборки на Windows с кириллицей в пути предусмотрен `tools/build-local.ps1`: исходники копируются в ASCII-каталог под LOCALAPPDATA. Он использует установленную локальную среду SDK; универсальный путь — Android Studio или Wrapper.

## Ресурсы обнаружения часов

Не удаляйте правила сохранения `android_wear_capabilities` в `res/raw/*_keep.xml`. Google Play services обращается к ним по имени. После R8 / shrinkResources оба APK должны содержать массив и свои capability:

- телефон — `dx_wrist_phone`;
- часы — `dx_wrist_watch`.

Официальные APK 1.2.4 проверены на этот контракт, подпись, выравнивание и `debuggable=false`. [Отчёт](../BUILD_VERIFICATION.md).

## Упаковка исходников

```text
python tools/package-source.py
```

Создаётся переносимый ZIP рядом с каталогом проекта. Из него исключены build, кэши, временная диагностика, локальные пути и закрытые ключи. Перед публикацией готовые APK проверяются отдельно.
