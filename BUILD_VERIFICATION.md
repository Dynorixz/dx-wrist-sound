# Проверка DX • Wrist Sound 1.2.4

16 октября 2026 года. Исходники и APK сохранены в существующей папке E:\Projects\Android\DX-Wrist-Sound. Старый путь E:\Новая папка больше не существует. Проверка 1.2.3 сохранена в VERIFICATION-1.2.3.md и RELEASE_VERIFICATION-1.2.3.md.

## Исправление

Причина — WatchRepository.applyVibration вызывал принудительный RINGER_MODE_VIBRATE при подтверждении ON_WRIST и получении CONFIG от телефона. Из-за этого ручной выбор мелодии часов перезаписывался.

Принудительное включение вибрации часов полностью удалено. Метод vibrate удалён из WatchSoundController. Датчик и CONFIG не вызывают изменение звука часов. watchVibration при чтении/записи всегда false, оба устройства отправляют в старом wire-поле false; часы игнорируют true от прежнего телефона. Переключатель «Вибрация часов» удалён из UI, вместо него поясняется сохранение системного звука часов.

Новый профиль по умолчанию: ON_WRIST -> телефон VIBRATE (0,1); OFF_WRIST -> телефон MAXIMUM/NORMAL; UNKNOWN не меняет звук. SoundProfileMigration в DataStore один раз задаёт новый профиль существующим установкам (sound_profile_version=1), сохраняя enabled, selected_node, alerts, debounce и старый снимок аудио. После миграции ручные изменения настроек телефона не сбрасываются.

WatchSoundController выполняет только releaseLegacyVibration при begin/refresh/stop. При отсутствии собственного снимка AudioManager не меняется. Если пользователь уже выбрал NORMAL или SILENT, старое владение удаляется без изменения громкости. Старые снимки SILENT/VIBRATE/нулевой громкости тоже только удаляются. Если старая версия оставила собственный VIBRATE и сохранила слышимый NORMAL, один раз завершается восстановление NORMAL/громкости. После этого новое владение звуком не приобретается. Отказ системы сохраняет pending cleanup и показывает локализованную подсказку с кнопкой системных настроек звука. Вручную включённый звук имеет приоритет над pending снимком.

Если часы уже находятся в вибрации без пригодного сохранённого снимка, выберите звук и мелодию в системных настройках часов один раз. Приложение больше не переводит их обратно в вибрацию. Включение громкости часов, синхронизация режимов прошивкой и слышимость входящего звонка физически не проверены.

## Проверки

Полная сборка tools/build-local.ps1 завершилась BUILD SUCCESSFUL за 5 минут 37 секунд: 181 задача, 67 выполнены, 114 up-to-date. Release для mobile/wear, shared:testDebugUnitTest, wear:testDebugUnitTest и lintRelease прошли. Проверка packaged capabilities mobile/dx_wrist_phone и wear/dx_wrist_watch прошла.

52 JVM-теста: 40 Shared + 12 Wear, failures=0 / errors=0. Прежние тесты автоматики, восстановления ночью, anti-flap и screen-lock сохранены. Новые 5 тестов проверяют профиль VIBRATE/MAXIMUM, переход с прежних настроек, сохранение остальных данных, однократность миграции и fresh install. 12 тестов WatchSoundController проверяют сохранение выбранной мелодии/громкости/тишины, отсутствие записи без владения, cleanup прежней вибрации только один раз, игнорирование старых mute-снимков, восстановление после отказа/перезапуска и приоритет ручного режима.

Lint: Mobile 0 ошибок / 8 предупреждений; Wear 0 / 4; Shared 0 / 1. Два прежних сообщения XML parser «Content is not allowed in prolog» в Wear lint не дали ошибок итогового отчёта. SHA-256 SoundProfileMigration, PersistentStore, WatchRepository и WatchSoundController совпали между source и ASCII build directory.

## Подписи и APK

tools/sign-release.ps1 -SkipBuild повторно использовал существующий release-ключ (создан 11 октября), не создавая новую подпись обновления. Оба release APK: один signer, v2=true/v3=true, debuggable=false, zipalign -c -P 16 4 прошёл; package=dev.dx.wristsound, versionName=1.2.4, versionCode=8. Сохранены discovery capabilities. Код и ресурсы вне META-INF побайтово совпали с Gradle APK, ZIP CRC корректны. classes.dex обоих приложений отличается от 1.2.3.

Release certificate SHA-256:

```text
d3c881d7e1a1a95a4570d6a194b5aa661614e8fbc1e95d001a5659396aea7618
```

Compatible certificate SHA-256 (прежние локальные установки):

```text
178153e7125b1a3cd139d0c73fc7ad77a54b36741ae2e26166819b34eee02c24
```

| APK | Байты | SHA-256 |
|---|---:|---|
| DX-Wrist-Sound-Phone-1.2.4-compatible.apk | 2532520 | 7B1C8F95F2EFCBB6B0DDDA4E28D5AC54123AFAA21C9F8EBB6F29A99A4898770C |
| DX-Wrist-Sound-Wear-1.2.4-compatible.apk | 4235049 | E978E5E9AA527FD1FBA8C97D973CB587AF42B83BC9BB9DA512BFD2131C9D3362 |
| DX-Wrist-Sound-Phone-1.2.4-release.apk | 2528900 | F2D0725168E017C845C14D2329DEDF068987DCFD907341184D0049BE698AEC8C |
| DX-Wrist-Sound-Wear-1.2.4-release.apk | 4233293 | D4A5010A6428912F705188D0AF74D3FB64D453D637E8FA63129D3D342436AC8F |

Оба устройства обновлять одной парой: Compatible поверх прежних debug-подписей; Release поверх 1.2.3-release. Не смешивать сертификаты. Ключ и DPAPI-пароль хранятся отдельно в закрытом каталоге %USERPROFILE%\.codex\signing\DX-Wrist-Sound и не включаются в исходники или архив проекта.

## Устройства и архив

adb devices пуст: установка и аппаратная проверка в этой сессии не выполнены. На устройствах проверить надевание/снятие, выбор звука/мелодии на часах, повторный вход/перезапуск/синхронизацию, снятие под PIN, ночной режим и настоящее оповещение о потере телефона.

Архив проекта включает исходный ZIP, обе пары APK, сертификат, инструкции и этот отчёт; без build, cache, remote attachments, временных QA-файлов, ключей и локальных настроек. Результаты облачного сохранения фиксируются в отдельном ARCHIVE_STATUS-1.2.4.json рядом с архивом после загрузки, чтобы не менять уже загруженный архив.