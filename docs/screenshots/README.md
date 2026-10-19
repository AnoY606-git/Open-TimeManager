# Скриншоты / Screenshots

[Русский](#русский) · [English](#english) · [На главную](../../README.md)

## Русский

Все 20 снимков сделаны **19 октября 2026 года** на выделенном эмуляторе
AOSP Android 16 / API 36 из **dev/debug-сборок `main`**, указанных ниже.
Два кадра настроек обновлены после добавления поиска и нового раздела
«Android и устройство»; остальные 18 сохранены из предыдущей съёмки интерфейса,
уведомления и месячного баланса. Это не опубликованная стабильная 1.1.0.
Номер версии обоих APK остаётся `1.1.0` (`versionCode = 3`);
новый стабильный релиз для этой галереи не создавался.

Использованы только вымышленные проекты и записи либо пустая новая установка,
не личный телефон или рабочие данные. Съёмка прежних 18 кадров предшествует
экспериментальному клиенту HyperIsland; новые кадры показывают только обзор
настроек на AOSP. В галерее нет кадров HyperOS или острова Xiaomi;
изображения не подтверждают совместимость с Now Bar, Live Alerts
и другими OEM-поверхностями.

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

Для прежних 18 кадров в проекты `Design Lab` и `Learning` через штатный предпросмотр и объединение
копии импортированы 24 демонстрационные записи. За сентябрь 2026 года сохранено
**166 ч 30 мин**; вручную заданный план **160 часов** даёт баланс **+06:30:00**.
Оценка по будням в форме равна 176 часам и не применяется автоматически.

За 19 октября добавлены два часа подтверждённой работы. Короткая настоящая
сессия запускалась, ставилась на паузу и продолжалась во время съёмки, поэтому
дневной итог и хронометр немного отличаются между кадрами. Их значения не подменялись.
Названия проектов и заметки одинаковы на обоих языках; локализуется интерфейс.
Два обновлённых кадра настроек сняты с новой пустой установки после UI-тестов;
таймер не запускался, записи или проекты для этих кадров не импортировались.

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
- Проверка сборки: `:app:assembleDebug`; release не собирался.
- Файл: `OTM.apk`, пакет `dev.opentimemanager.app`.
- AVD: `otm_ui_api36`, Android 16 / API 36, Google APIs x86_64.

**18 кадров, кроме настроек:** исходники
[`6eba066d47e5e0acc364cf707c3c0bd1b807deb4`](https://github.com/AnoY606-git/Open-TimeManager/commit/6eba066d47e5e0acc364cf707c3c0bd1b807deb4),
SHA-256 APK `f14fe987cf0122129debd1864eddeaf29ff74b888c02d501be5402b00131dbd3`.

**Два обновлённых кадра `ru-settings.png` и `en-settings.png`:** исходники
[`b8fcdf1f4fbac6db33481edee2629483e5f4587e`](https://github.com/AnoY606-git/Open-TimeManager/commit/b8fcdf1f4fbac6db33481edee2629483e5f4587e),
SHA-256 APK `dc30320408082053b9f4741f27f64c76b9f6639deeddb5e9e26c532194c19798`.
Это тот же код, который прошёл 77 UI-тестов на API 36, включая настоящее окно
320 dp с 200% шрифтом и экранной клавиатурой. Проверочные кадры узкого окна
не входят в эту галерею; основные снимки по-прежнему имеют масштаб шрифта 100%.

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
AOSP Android 16 / API 36 emulator and the **`main` dev/debug builds** identified
above. The two settings scenes were refreshed after adding search and Android
and device; the remaining 18 scenes retain their earlier UI, notification and
monthly-balance capture. They do not show the published stable 1.1.0.
Both APKs report `1.1.0` (`versionCode = 3`); no new stable release was created
for this gallery. Only made-up data or a fresh empty installation was used,
never a personal phone or work records. The table above links to every scene
in both languages. The earlier 18 captures predate the experimental HyperIsland
client, while the refreshed pair shows only the settings overview on AOSP.
The gallery contains no HyperOS or Xiaomi island scenes and does not establish
compatibility with Now Bar, Live Alerts, or other OEM surfaces.

For the earlier 18 scenes, `Design Lab` and `Learning` contain 24 demonstration entries imported
through the normal backup preview and Merge flow. September 2026 has **166 hours
30 minutes** of confirmed work; the manually saved **160-hour** plan gives
**+06:30:00**. The optional weekday estimate is 176 hours, not an automatically
applied norm. Two hours of confirmed work were added for 19 October. A genuine
short timer session was paused and resumed during capture, so daily totals and
the session chronometer vary slightly. Project names and notes remain the same;
the app interface is localized. The two refreshed settings scenes use a fresh
empty installation after UI testing; no timer was started and no records or
projects were imported for these scenes.

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

The two source commits and their APK hashes are listed separately above,
alongside the debug task, package and AVD. The refreshed settings pair uses
`b8fcdf1f4fbac6db33481edee2629483e5f4587e`, whose code passed 77 UI tests on API 36,
including a real 320 dp window at 200% font scale with the software keyboard.
Those narrow-window QA captures are not part of this gallery; the main images
remain at 100% font scale.
The APK, demo backup, local helpers and SDK are not committed. To refresh the
gallery, explicitly select a dedicated emulator, navigate the actual interface
in both languages, inspect every image and both README galleries, and record
the real source commit, settings and APK checksum. Do not reuse old provenance.
An integration-settings scene captured on AOSP can illustrate that setting only.
An island gallery needs an actual supporting firmware, OTM approval, and a
verified result. Do not present a mockup, generated image, or copied screenshot
as the application running on HyperOS.
