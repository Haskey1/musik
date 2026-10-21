# musik для Android

Нативный клиент на Kotlin и Jetpack Compose — те же возможности, что у iOS-приложения в `ios/`:
вход по логину и паролю, главная с полками миксов, библиотека с поиском, страницы артиста
(с фото во всю ширину) и альбома, плеер с волной, лайками, свайпами, цветом фона по обложке,
6-полосным эквалайзером и «скрыть трек/артиста», офлайн-режим и события прослушивания для радио.

Воспроизведение — Media3 (ExoPlayer + `MediaSessionService`): управление со шторки, экрана
блокировки, Bluetooth и проводных наушников. minSdk 26 (Android 8.0).

## Сборка

APK собирает GitHub Actions (`.github/workflows/android.yml`) на каждый пуш, который меняет
`android/`. Готовый файл — в артефактах прогона (`musik-android-<номер>.apk`), его можно ставить
на телефон напрямую. Ошибки компиляции видны прямо в прогоне как аннотации.

Настройки репозитория (Settings → Secrets and variables → Actions):

| Где | Имя | Что |
|-----|-----|-----|
| Variables | `MUSIK_SERVER_URL` | адрес сервера, встраивается в приложение; поле адреса на экране входа скрыто |
| Secrets | `ANDROID_KEYSTORE_BASE64` | keystore для подписи, в base64 |
| Secrets | `ANDROID_KEYSTORE_PASSWORD` | пароль keystore |
| Secrets | `ANDROID_KEY_ALIAS` | alias ключа |
| Secrets | `ANDROID_KEY_PASSWORD` | пароль ключа (можно не задавать, если совпадает с паролем keystore) |

Подпись должна быть одной и той же у всех сборок, иначе новая версия не встанет поверх старой.
Без секретов CI подписывает debug-ключом раннера (он меняется от прогона к прогону) и пишет
предупреждение. Номер версии берётся из номера прогона, поэтому каждая сборка новее предыдущей.

Новый ключ, если понадобится:

```bash
keytool -genkeypair -keystore musik-release.jks -storetype PKCS12 -alias musik \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=musik Android"
base64 -w0 musik-release.jks   # → ANDROID_KEYSTORE_BASE64
```

Локально: `cd android && gradle assembleRelease` (JDK 17, Android SDK 35), адрес сервера —
переменной окружения `MUSIK_SERVER_URL`.

## Вход

`POST /api/auth/token` с логином, паролем и именем устройства выдаёт персональный токен
устройства; дальше всё идёт с `Authorization: Bearer`. Если сервер не умеет выдавать токены
(404/405/409), приложение входит как сайт — `POST /api/auth/login` с cookie-сессией.
Пустой логин — вход одного владельца по общему паролю. Долгое нажатие на логотип показывает
поле адреса сервера и вход по готовому токену.

## Устройство

```
app/src/main/java/app/musik/android/
  MusikApp.kt            синглтоны: настройки, API, состояние, плеер, офлайн, эквалайзер
  AppState.kt            вход/выход, тосты, наборы любимого
  data/                  Api (OkHttp), модели, Settings, ArtistPhotos, OfflineStore
  playback/              PlayerController (логика волны и событий), PlaybackService, Equalizer
  ui/                    тема, общие компоненты, навигация, экраны
```

`PlayerController` повторяет `ios/Musik/Playback/PlayerController.swift`: старт → поток →
`track_start` → `progress` каждые ~4 с → `track_end`/`skip` → следующий трек от сервера.
События несут `event_id` (повтор с тем же id при обрыве) и `impression_id` из очереди.
Следующие два трека скачиваются заранее; если сервер пропал, плеер продолжает с телефона
(предзагруженное, потом сохранённое) и раз в 15 с проверяет связь, а когда она вернулась —
на границе трека снова включает онлайн-радио.
