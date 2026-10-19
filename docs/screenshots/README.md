# Скриншоты / Screenshots

[Русский](#русский) · [English](#english) · [На главную](../../README.md)

## Русский

Все 20 снимков сделаны **19 октября 2026 года** на выделенном эмуляторе
AOSP Android 16 / API 36 из **dev/debug-сборки `main`**, указанной ниже. Они показывают
OTM после обновлений интерфейса, уведомления и месячного баланса, а не опубликованную
стабильную 1.1.0. Номер версии APK пока остаётся `1.1.0` (`versionCode = 3`);
новый стабильный релиз для этой галереи не создавался.

Использованы только вымышленные проекты и записи, не личный телефон или рабочие данные.
Съёмка предшествует добавлению экспериментального клиента HyperIsland. В галерее
нет кадров HyperOS, острова Xiaomi или его настроек; изображения не подтверждают
совместимость с Now Bar, Live Alerts и другими OEM-поверхностями.

### Что есть в галерее

| Экран | Русский | English |
| --- | --- | --- |
| Таймер на паузе и дневная цель | [ru-timer.png](ru-timer.png) | [en-timer.png](en-timer.png) |
| История записей | [ru-history.png](ru-history.png) | [en-history.png](en-history.png) |
| Баланс прошлого месяца | [ru-reports.png](ru-reports.png) | [en-reports.png](en-reports.png) |
| Уведомление Android 16 | [ru-notification.png](ru-notification.png) | [en-notification.png](en-notification.png) |
| Настройки | [ru-settings.png](ru-settings.png) | [en-settings.png](en-settings.png) |
| Справка | [ru-help.png](ru-help.png) | [en-help.png](en-help.png) |
| Проект по умолчанию | [ru-default-project.png](ru-default-project.png) | [en-default-project.png](en-default-project.png) |
| Предупреждение о копии без пароля | [ru-backup-warning.png](ru-backup-warning.png) | [en-backup-warning.png](en-backup-warning.png) |
| Создание месячного плана | [ru-monthly-plan.png](ru-monthly-plan.png) | [en-monthly-plan.png](en-monthly-plan.png) |
| Тёмная тема | [ru-timer-dark.png](ru-timer-dark.png) | [en-timer-dark.png](en-timer-dark.png) |

В проекты `Design Lab` и `Learning` через штатный предпросмотр и объединение
копии импортированы 24 демонстрационные записи. За сентябрь 2026 года сохранено
**166 ч 30 мин**; вручную заданный план **160 часов** даёт баланс **+06:30:00**.
Оценка по будням в форме равна 176 часам и не применяется автоматически.

За 19 октября добавлены два часа подтверждённой работы. Короткая настоящая
сессия запускалась, ставилась на паузу и продолжалась во время съёмки, поэтому
дневной итог и хронометр немного отличаются между кадрами. Их значения не подменялись.
Названия проектов и заметки одинаковы на обоих языках; локализуется интерфейс.

### Параметры и происхождение

- PNG сняты напрямую в **720 × 1560**, 280 dpi, портретная ориентация,
  масштаб шрифта 100%. Масштабирования, ретуши, генерации и коллажей нет.
- Светлая тема для основных экранов, тёмная — для двух кадров таймера;
  цвета устройства выключены. Язык Android — английский, OTM — RU или EN.
- Стандартный demo mode фиксирует часы и батарею только в строке состояния.
  Для кадров уведомления служебные уведомления эмулятора временно отложены
  штатной функцией Android. Текст, прогресс и действия OTM не менялись.
- Показан обычный нативный `ProgressStyle` AOSP Android 16 / API 36, Live Updates
  выключены. Это настоящие снимки эмулятора, не макеты HyperIsland. Кадры не
  доказывают продвижение уведомления, OEM-анимацию, работу нативного таймера
  HyperOS, приватность OEM lockscreen/AOD или расход батареи.
- Исходники приложения: [`6eba066d47e5e0acc364cf707c3c0bd1b807deb4`](https://github.com/AnoY606-git/Open-TimeManager/commit/6eba066d47e5e0acc364cf707c3c0bd1b807deb4).
- Проверка сборки: `:app:assembleDebug`; release не собирался.
- Файл: `OTM.apk`, пакет `dev.opentimemanager.app`.
- SHA-256 APK: `f14fe987cf0122129debd1864eddeaf29ff74b888c02d501be5402b00131dbd3`.
- AVD: `otm_ui_api36`, Android 16 / API 36, Google APIs x86_64.

### Обновление снимков

APK, демонстрационная копия, служебные скрипты и SDK в Git не добавляются.
При следующем обновлении явно выберите отдельный эмулятор, пройдите настоящий
интерфейс на обоих языках, проверьте все изображения и обе главные страницы.
Запишите актуальные commit, параметры и SHA-256, не переносите сведения прежней сборки.
Снимок настройки интеграций на AOSP можно добавить только как снимок настройки;
для галереи острова нужны настоящая поддерживающая прошивка, допуск OTM и
проверенный результат. Не выдавайте макет, сгенерированное или скопированное
изображение за работу приложения на HyperOS.

## English

All 20 screenshots were captured on **19 October 2026** using a dedicated
AOSP Android 16 / API 36 emulator and the **`main` dev/debug build** identified
above, after the UI, notification and monthly-balance updates. They do not show the published stable
1.1.0. The APK still reports `1.1.0` (`versionCode = 3`); no new stable release
was created for this gallery. Only made-up data was used, never a personal phone
or work records. The table above links to every scene in both languages.
Capture predates the experimental HyperIsland client. The gallery contains no
HyperOS, Xiaomi island, or island-settings scenes; it does not establish
compatibility with Now Bar, Live Alerts, or other OEM surfaces.

The `Design Lab` and `Learning` projects contain 24 demonstration entries imported
through the normal backup preview and Merge flow. September 2026 has **166 hours
30 minutes** of confirmed work; the manually saved **160-hour** plan gives
**+06:30:00**. The optional weekday estimate is 176 hours, not an automatically
applied norm. Two hours of confirmed work were added for 19 October. A genuine
short timer session was paused and resumed during capture, so daily totals and
the session chronometer vary slightly. Project names and notes remain the same;
the app interface is localized.

PNGs were captured directly at **720 × 1560**, 280 dpi, portrait orientation,
100% font scale, device colors off, with light and dark themes as shown.
There was no resizing, retouching, generation or compositing. Android remained
in English while OTM used RU or EN, so notification-shade system labels stay English.
Demo mode stabilizes the status-bar clock and battery only. Emulator service
notifications were temporarily snoozed through Android for notification captures;
OTM's content, progress and actions were not altered. These show standard Android
16 / API 36 `ProgressStyle` with Live Updates off. They are genuine emulator
captures, not HyperIsland mockups, and do not establish promotion, OEM animations,
native HyperOS timing, OEM lockscreen/AOD privacy, or physical-device battery performance.

The captured source commit, debug task, package, APK hash and AVD are listed above.
The APK, demo backup, local helpers and SDK are not committed. To refresh the
gallery, explicitly select a dedicated emulator, navigate the actual interface
in both languages, inspect every image and both README galleries, and record
the real source commit, settings and APK checksum. Do not reuse old provenance.
An integration-settings scene captured on AOSP can illustrate that setting only.
An island gallery needs an actual supporting firmware, OTM approval, and a
verified result. Do not present a mockup, generated image, or copied screenshot
as the application running on HyperOS.
