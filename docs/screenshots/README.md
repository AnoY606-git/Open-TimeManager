# Скриншоты / Screenshots

> [!NOTE]
> **Все 16 кадров пересняты с подписанной 1.1.0 на эмуляторе 18 октября 2026 года.**
> Десять прежних PNG заменены, шесть новых сценариев добавлены. Телефон не использовался.

## Параметры съёмки 1.1.0

| Поле | Фактическая съёмка |
| --- | --- |
| Приложение | Подписанная `1.1.0`, `versionCode = 3`, после успешной runtime-проверки |
| Эмулятор | Android 16 / API 36, AOSP x86_64, профиль Pixel 5; AVD `OTM_Screenshots_API36` |
| Разрешённая цель | Только принадлежащий владельцу проекта эмулятор `emulator-5554`; не личный телефон |
| Размер | Захват 1080 × 2340; готовые PNG 720 × 1560 с сохранением пропорций |
| Оформление | Портрет, жестовая навигация, стандартный масштаб текста, dynamic color выключен |
| Набор | RU и EN: по семь светлых экранов и одному тёмному таймеру, всего 16 PNG |
| Исходный код снятого APK | `bd2c0e65bdd72dba49ddaba1fe0998771f31ffa9` |
| Дата и APK | 2026-10-18, `Open-TimeManager-1.1.0.apk`, все 16 PNG проверены визуально |
| SHA-256 снятого APK | `fe73c9aecad4bad9f449e42ee6794373321ca8ddc93332961241a11c29f800ba` |

Это SHA фактически снятого APK, а не последующего коммита галереи. После добавления
изображений итоговый APK пересобирается для актуализации встроенных VCS-метаданных;
байтовое совпадение исполняемого кода и ресурсов проверяется отдельно. Окончательная
сумма находится в release assets. Снимки сами по себе не заменяют проверку релиза.

## Кадры и имена файлов

Существующие 10 имён сохранены, но **каждый кадр снят заново с UI 1.1**.
Новые сценарии — руководство, выбор проекта по умолчанию и предупреждение перед
созданием незащищённой копии `.otmplain`.

| Экран / Scene | Русский | English | Результат |
| --- | --- | --- | --- |
| Таймер / Timer, light | `ru-timer.png` | `en-timer.png` | Переснято / Captured |
| История / History, light | `ru-history.png` | `en-history.png` | Переснято / Captured |
| Отчёты / Reports, light | `ru-reports.png` | `en-reports.png` | Переснято / Captured |
| Обзор пяти разделов настроек / Settings overview, light | `ru-settings.png` | `en-settings.png` | Переснято / Captured |
| Руководство / Help, light | `ru-help.png` | `en-help.png` | Добавлено / Captured |
| Выбор проекта по умолчанию / Default-project picker, light | `ru-default-project.png` | `en-default-project.png` | Добавлено / Captured |
| Предупреждение о `.otmplain` / Plaintext-backup warning, light | `ru-backup-warning.png` | `en-backup-warning.png` | Добавлено / Captured |
| Таймер / Timer, dark | `ru-timer-dark.png` | `en-timer-dark.png` | Переснято / Captured |

## Только демонстрационные данные

Использован тот же синтетический набор из стабильной 1.0, сохранённый
при обновлении до 1.1 **без удаления приложения и очистки данных**. Он не заменялся
результатами тестов или новым пустым набором ради красивого кадра.

Дата ручных записей — 17 октября 2026 года. Пользовательские проекты — только
`Design Lab` и `Learning`; служебный «Без проекта» создаётся приложением.
Записи из обычной формы истории:

| Проект | Начало | Минуты | Заметка |
| --- | --- | --- | --- |
| Design Lab | 08:30 | 90 | Landing page concept |
| Learning | 10:15 | 60 | Compose study |
| Design Lab | 11:30 | 30 | Icon sketches |

Три ручные записи дают 3 часа: 2 часа `Design Lab` и 1 час `Learning`. Если кадр
таймера показывает короткую сессию на паузе, это должна быть настоящая сессия;
её подтверждённые секунды учитываются в итогах, а не дорисовываются. При другой
текущей дате явно выберите период отчёта, включающий демонстрационные записи.

В кадрах истории включён реальный поиск `c`: он находит все три исходные заметки
и скрывает короткие проверочные сессии без заметок. Записи не изменялись и не
удалялись. В отчёте за 12–18 октября видны 3:03 с подтверждённым временем реальных
smoke-сессий; короткая текущая пауза показывает 0:00:02, а не дорисованную длительность.

## Порядок обновления владельцем проекта

1. Завершить проверки и smoke-проверку подписанного APK. Проверки, способные
   сбросить данные, выполнять на отдельной тестовой установке, не на наборе для
   подтверждения обновления 1.0 → 1.1.
2. Перед любым захватом подтвердить AVD `OTM_Screenshots_API36` и serial
   `emulator-5554`. Не обращаться к физическому телефону и не очищать его данные.
3. Проверить сохранность перечисленных проектов и записей после обновления.
   Переключать RU/EN, темы и экраны через настоящий интерфейс приложения.
4. Снять все 16 кадров. Светлый кадр настроек должен показывать обзор пяти
   разделов, а не прежнюю длинную страницу с оформлением и переключателями.
   Предупреждение о копии открывать отдельной командой создания `.otmplain`.
5. Разрешено только равномерное уменьшение 1080 × 2340 → 720 × 1560.
   Запрещены Photoshop, генерация картинок, дорисовка, замена текста, таймера,
   предупреждений или сборка экрана из фрагментов. System UI demo mode может
   фиксировать часы строки состояния на 12:34 и скрывать уведомления, но не
   подменять часы и результаты внутри приложения.
6. Вручную проверить PNG и оба README, заполнить SHA/дату/APK выше и только после
   наличия всех файлов снять пометки об ожидаемой съёмке в трёх документах.

Личные проекты, заметки, backup-файлы, уведомления и данные клиентского или
личного телефона не используются. Эти кадры показывают UI эмулятора, а не
доказывают прохождение на физическом устройстве, экономию батареи или продвижение
Live Updates: на API 36 уведомление может оставаться обычным. Результаты релизных
проверок публикуются отдельно, когда они действительно выполнены.

## English

**All 16 screenshots were captured on 2026-10-18 from the signed 1.1.0 build.**
Ten old files were replaced and six workflow scenes added. All files were visually reviewed.

The signed **1.1.0 / versionCode 3** build was captured after its APK smoke check,
using only the owned **Android 16 / API 36** emulator:
AVD **`OTM_Screenshots_API36`**, serial **`emulator-5554`**, AOSP x86_64 / Pixel 5
profile. Native captures are **1080 × 2340**, resized proportionally to
**720 × 1560**. Use portrait orientation, gesture navigation, normal font scale,
and dynamic colors off. Each language has seven light-theme screens and one
dark timer; all 16 filenames and scenes are listed above.

The **synthetic stable-1.0 fixture was preserved through the in-place upgrade**:
`Design Lab`, `Learning`, and the three dated entries/notes in the table. Do not
uninstall, clear data, or replace that fixture with test results. Run destructive
test setup separately. Choose a report period containing 17 October 2026; any
paused timer and its extra confirmed seconds must be genuine.

Capture the actual UI in each app language, including the five-section Settings
overview, offline Help, default-project picker, and explicit plaintext-backup
creation warning. Only proportional resizing is permitted: no generated images,
Photoshop, compositing, replacement text, or fabricated views. System UI demo mode
may stabilize the status bar at 12:34 and hide notifications; it must not alter
the app's clock or results. Never use a client/private phone or personal data.

Captured APK source: `bd2c0e65bdd72dba49ddaba1fe0998771f31ffa9`; its SHA-256 is in
the table above. The later gallery commit only changes documentation/images and
embedded VCS metadata; executable/resource equivalence is checked before release.
History uses the real `c` search to show the three original notes, not empty-note
smoke sessions. No records were deleted. Screenshots do not establish physical-device
testing, battery savings or Live Update promotion; base API 36 can retain ordinary presentation.
