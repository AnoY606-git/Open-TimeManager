# Скриншоты / Screenshots

> [!NOTE]
> **Съёмка 1.1.0 запланирована, но ещё не выполнена.** Сейчас в каталоге остаются
> 10 прежних PNG; их нужно переснять, а шесть новых — добавить. Разметка галерей
> в обоих README подготовлена заранее и не доказывает готовность релиза.

## План съёмки 1.1.0

| Поле | План / запись после съёмки |
| --- | --- |
| Приложение | Кандидат стабильной `1.1.0`, `versionCode = 3`; съёмка после smoke-проверки подписанного APK |
| Эмулятор | Android 16 / API 36, AOSP x86_64, профиль Pixel 5; AVD `OTM_Screenshots_API36` |
| Разрешённая цель | Только принадлежащий владельцу проекта эмулятор `emulator-5554`; не личный телефон |
| Размер | Захват 1080 × 2340; готовые PNG 720 × 1560 с сохранением пропорций |
| Оформление | Портрет, жестовая навигация, стандартный масштаб текста, dynamic color выключен |
| Набор | RU и EN: по семь светлых экранов и одному тёмному таймеру, всего 16 PNG |
| Исходный код APK | **TODO владельца:** полный SHA итогового кодового коммита. `e6fe152…` — только ориентир кандидата; исправления могут его заменить |
| Фактическая съёмка | **TODO владельца:** дата, точный APK и подтверждение проверки всех 16 файлов |

SHA нужно взять из источников фактически снятого APK, а не из последующего
коммита галереи. Пока TODO не заполнены, здесь нет заявления о завершённой
съёмке, публикации, проверке подписи или независимом аудите.

## Кадры и имена файлов

Существующие 10 имён сохраняются, но **каждый кадр снимается заново с UI 1.1**.
Новые сценарии — руководство, выбор проекта по умолчанию и предупреждение перед
созданием незащищённой копии `.otmplain`.

| Экран / Scene | Русский | English | План |
| --- | --- | --- | --- |
| Таймер / Timer, light | `ru-timer.png` | `en-timer.png` | Переснять / Reshoot |
| История / History, light | `ru-history.png` | `en-history.png` | Переснять / Reshoot |
| Отчёты / Reports, light | `ru-reports.png` | `en-reports.png` | Переснять / Reshoot |
| Обзор пяти разделов настроек / Settings overview, light | `ru-settings.png` | `en-settings.png` | Переснять / Reshoot |
| Руководство / Help, light | `ru-help.png` | `en-help.png` | Добавить / Add |
| Выбор проекта по умолчанию / Default-project picker, light | `ru-default-project.png` | `en-default-project.png` | Добавить / Add |
| Предупреждение о `.otmplain` / Plaintext-backup warning, light | `ru-backup-warning.png` | `en-backup-warning.png` | Добавить / Add |
| Таймер / Timer, dark | `ru-timer-dark.png` | `en-timer-dark.png` | Переснять / Reshoot |

## Только демонстрационные данные

Для пересъёмки нужен тот же синтетический набор из стабильной 1.0, сохранённый
при обновлении до 1.1 **без удаления приложения и очистки данных**. Не заменяйте
его результатами тестов или новым пустым набором ради красивого кадра.

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

**The 1.1.0 reshoot is planned, not completed.** The ten existing PNG files still
need replacement; six new workflow files await capture. Both main README
galleries are prepared in advance, not evidence of release readiness.

The project owner will capture the signed **1.1.0 / versionCode 3** candidate
after its APK smoke check, using only the owned **Android 16 / API 36** emulator:
AVD **`OTM_Screenshots_API36`**, serial **`emulator-5554`**, AOSP x86_64 / Pixel 5
profile. Native captures are **1080 × 2340**, resized proportionally to
**720 × 1560**. Use portrait orientation, gesture navigation, normal font scale,
and dynamic colors off. Each language has seven light-theme screens and one
dark timer; all 16 filenames and scenes are listed above.

Reuse the **synthetic stable-1.0 fixture preserved through the in-place upgrade**:
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

**Owner TODO:** record the full source SHA of the captured APK, capture date, and
exact APK in the metadata table. `e6fe152…` is only a candidate-code reference and
may be superseded by fixes; it is not a completed capture or publication/audit
record. After verifying all 16 files and both galleries, remove the pending notes
from these three documents. Screenshots do not establish physical-device testing,
battery savings, or Live Update promotion; an ordinary notification on API 36 is
not evidence of a failed capture.
