# VladiMir-TV 3.0 — проверка и целевая структура

## Проверенное состояние исходников
- Android Kotlin, один модуль `app`, `MainActivity.kt` содержит UI, сетевые запросы, M3U, плеер и локальный HTTP-сервер.
- ExoPlayer Media3: HLS, DASH, RTSP и стандартные источники; реальная поддержка кодеков зависит от устройства.
- Три языка ru/de/en реализованы через `tr()`; часть технических сообщений пока не переведена.
- ТВ-плейлисты M3U по URL, отдельный URL видеотеки M3U, часы и пультовый выбор каналов.
- Не реализованы: Xtream Codes, Stalker/MAG, XMLTV EPG, архив, YouTube/Filmix поиск, полноценный фон.
- APK не собран локально: в среде отсутствуют Gradle и Android SDK; GitHub Actions настроен для сборки.

## Целевая архитектура (план, НЕ готовая реализация)
```
app/src/main/java/com/vladimir/tv/
  MainActivity.kt                 # навигация и запуск
  model/
    Channel.kt, VodItem.kt, Program.kt, PlaylistSource.kt
  data/
    SettingsRepository.kt, PlaylistRepository.kt
    providers/
      M3uProvider.kt, XtreamProvider.kt, PortalProvider.kt
    epg/
      XmlTvParser.kt, EpgRepository.kt
    catchup/
      CatchupResolver.kt
  player/
    PlayerController.kt, PlayerScreen.kt, PlaybackErrorHandler.kt
  ui/
    HomeScreen.kt, ChannelScreen.kt, VodScreen.kt
    ProviderSettingsScreen.kt, RemoteOverlay.kt
  remote/
    DpadNavigation.kt, PhonePairingServer.kt
app/src/main/res/values/strings.xml
app/src/main/res/values-de/strings.xml
app/src/main/res/values-ru/strings.xml
app/src/test/...                 # parser and repository tests
```

## Правила реализации
1. Разделить источники Live TV и VOD: категории `group-title` не должны превращать ТВ-каналы в фильмы.
2. Источник: URL M3U, Xtream (base URL/user/password), Portal (только поддерживаемые и разрешённые методы авторизации).
3. EPG: XMLTV по URL, привязка `tvg-id`, временные зоны и кэш; архив — только при наличии catch-up API провайдера.
4. Сначала воспроизводить видео, затем показывать поверх него меню с D-pad фокусом; смена потока только по OK.
5. Сохранять пропорции по умолчанию, Zoom и Stretch сделать дополнительными режимами.
6. Языки ru/de/en для ВСЕХ новых экранов, строки в ресурсах, не хардкодить в UI.
7. Хранить пароли отдельно от экспортируемых плейлистов, не включать в логи или публичный GitHub.
8. Внешние сайты открывать в браузере/официальном приложении; не обещать извлечение защищённых потоков.
9. Отдельные тесты M3U, XMLTV, Xtream parsing, фокус пульта, жизненный цикл плеера, устройство Fire TV.

## Что изменено в этой проверочной сборке
- Убрана вкладка «Сериалы».
- Категории ТВ не переклассифицируются по словам «кино/film».
- В меню поверх видео сначала выбирается категория, затем канал; до выбора канала поток не меняется.
- Полноэкранный системный режим при запуске воспроизведения, в том числе на телефоне.
- Настройка Fit / Zoom / Stretch (Zoom может обрезать края; Stretch искажает пропорции).
- Переименованы версия и GitHub Actions artifact.

## Проверки перед выпуском
- `gradle assembleDebug` в GitHub Actions, устранить ошибки компиляции.
- Установить APK на Fire TV и Android телефон.
- Проверить D-pad, OK, Back, полноэкранность, HLS/TS/DASH/RTSP с законными тестовыми потоками.
- Отдельно проверить EPG и архив на реальном провайдере после реализации.
