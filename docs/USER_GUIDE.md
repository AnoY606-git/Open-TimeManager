# Руководство пользователя / User guide

[Русский](#русский) · [English](#english) · [Документация](README.md)

## Русский

Это руководство для текущей ветки `main` OTM (Open TimeManager) после выпуска
1.1.0. Мастер первого запуска, месячные планы, поиск по разделам настроек, обновлённый дневной прогресс
уведомления и экспериментальный клиент HyperIsland пока доступны в dev-сборках.
Последний стабильный релиз — 1.1.0;
его возможности описаны в [заметках к выпуску](releases/1.1.0.md). Справка с поиском также доступна
без интернета в «Настройки → Как работать с приложением».

### Подготовка при первом запуске

На новой установке OTM предлагает необязательный мастер: сведения об Android и
семействе устройства, состояние уведомлений и фоновых ограничений, затем быстрый
доступ к системным возможностям. Производитель не выдаётся за точное название
прошивки: если её нельзя подтвердить, мастер прямо сообщает об этом.

Разрешение на уведомления запрашивается только по нажатию кнопки. Мастер не
включает автозапуск, не снимает ограничения батареи и не включает Live Updates
самостоятельно. После возврата из системных настроек статусы проверяются заново.

Любой шаг можно пропустить. «Пропустить», системная кнопка «Назад» или завершение
мастера сохраняются только на этом устройстве: при следующем запуске он не
появится снова. При обновлении существующей установки мастер автоматически не
навязывается. Открыть его вручную можно в «Настройки → Android и устройство».
Этот выбор не переносится в резервных копиях. Пропуск не мешает учёту времени
внутри приложения.

### Первая рабочая сессия

1. Откройте «Таймер» и выберите проект. Если группировать работу пока не нужно,
   оставьте «Без проекта».
2. Нажмите «Начать».
3. На время перерыва нажмите «Пауза», затем «Продолжить».
4. В конце работы нажмите «Завершить». Запись появится в «Истории».

К сессии можно добавить заметку — не забудьте сохранить её в редакторе.
Дневная цель помогает следить за прогрессом, но не останавливает таймер
и не включает будильник.

Одновременно работает один таймер. Если выбрать другой проект во время сессии,
приложение попросит подтвердить переключение: прежняя запись завершится,
а для нового проекта начнётся новая.

### Проекты

Создайте проект на экране таймера, задайте имя и цвет. Когда проект больше не
нужен, архивируйте его: он исчезнет из списка для новой работы, но его записи
останутся в истории. Сначала завершите таймер этого проекта или разберите
запись, которая требует проверки. «Без проекта» — постоянный вариант,
его архивировать нельзя.

**Проект по умолчанию** выбирается в «Настройки → Учёт и проекты».
Он будет предложен для новой работы, но не изменит текущую сессию и не отменит
ваш выбор на открытом таймере. Архивный или удалённый проект больше не используется.
Чтобы убрать предпочтение, выберите «Автоматический выбор».

Виджет и плитка могут сразу начать работу с проектом по умолчанию. Если он не
задан, быстрый запуск сработает только при одном доступном проекте; иначе
откроется приложение, чтобы вы выбрали нужный. Этот выбор хранится на устройстве
и не переносится в копии.

### История и ручные записи

В «Истории» можно найти запись по проекту или заметке, исправить её либо добавить
работу, которую вы не засекали. Для ручной записи укажите дату, время начала,
длительность и проект. Старые записи открываются кнопкой «Загрузить ещё»;
в выборе проекта есть отдельный поиск.

Изменение заметки или проекта не меняет точное время исходной записи. Если
изменить время, прежние интервалы заменятся введённой длительностью.
Архивный проект старой записи можно сохранить при редактировании.
Удаление записи после подтверждения отменить нельзя.

Если приложение сообщает, что запись уже изменилась, закройте форму и откройте
её заново — так вы не перезапишете более свежие данные.

### Отчёты и CSV

Выберите неделю, месяц или собственный период до 10 лет. Границы периода
определяются текущим часовым поясом. В отчёт попадают подтверждённые закрытые
интервалы: текущий отрезок добавится после паузы, завершения или смены проекта.
Поэтому работающий таймер может показывать больше, чем отчёт.

В режиме месяца стрелки переключают календарные месяцы, а «Прошлый месяц»
и «Текущий месяц» позволяют быстро вернуться к нужному периоду. В карточке
«Баланс месяца» задайте план часов и минут именно для выбранного месяца:
приложение покажет разность «подтверждённое время − план». Знак «+» означает
время сверх плана, «−» — недобор в закончившемся месяце или остаток до плана
в текущем. Например, 152 часа при плане 160 часов дадут `−08:00:00`.
Пока план не задан, недобор не рассчитывается. Нулевой план допустим,
например для месяца без работы; «Убрать план» возвращает состояние без плана.

Оценка по будням использует понедельник–пятницу и текущую дневную цель,
но не знает праздников, отпусков и вашего графика. Она заполняет форму только
по нажатию; проверьте и сохраните её самостоятельно. Изменение дневной цели
не меняет уже сохранённые планы месяцев. Планы хранятся локально и не входят
в резервные копии; восстановление записей их не заменяет.

Кнопка экспорта сохраняет выбранный период в CSV через системное окно Android.
Его можно открыть в таблицах. **CSV не подходит для восстановления приложения**
и может содержать названия проектов и заметки — не публикуйте его случайно.

### Копии и восстановление

#### Сохранить копию

1. Поставьте таймер на паузу или завершите его: ещё не закрытый рабочий отрезок
   в копию не попадёт.
2. Откройте «Настройки → Копии данных».
3. Выберите зашифрованную копию, придумайте длинную уникальную парольную фразу
   и сохраните файл отдельно от устройства.
4. Для проверки откройте сохранённый файл через восстановление и убедитесь,
   что предпросмотр показывает ожидаемые данные. Применять восстановление не нужно.

| Формат | Когда использовать |
| --- | --- |
| **`.otmbackup` — с паролем** | Обычный и рекомендуемый вариант. Содержимое зашифровано. Забытый пароль восстановить нельзя. |
| **`.otmplain` — без пароля** | Только если вам нужен открытый файл и вы понимаете риск. Любой получатель сможет прочитать и изменить его. |

Для нового пароля нужны не менее 12 символов и не более 1024 байт UTF-8.
Пробелы учитываются; старые копии с более короткими паролями продолжают открываться.
Если зашифрованная копия не открылась, проверьте пароль и сам файл: приложение
не обходит пароль и не превращает такую копию в открытую.

Копии содержат проекты, заметки, сохранённые интервалы и основные настройки:
тему, цвета устройства, скрытие названий и дневную цель. Язык, проект по
умолчанию, планы месяцев и выбор «Live Updates и HyperIsland» остаются на устройстве
и не входят в файлы копий. Старые зашифрованные копии поддерживаются.
Приложение принимает файл до 4 МиБ и до 20 000 проектов, записей и интервалов
каждого вида.

Перед созданием или открытием `.otmplain` появляется предупреждение.
Проверка файла обнаруживает случайное повреждение, но **не доказывает,
кто его создал и не изменил ли его кто-то намеренно**. Открывайте копии
только из источников, которым доверяете.

#### Восстановить данные

1. Завершите текущий таймер. Восстановление недоступно и на паузе, и пока запись
   требует проверки.
2. Выберите файл. Для зашифрованной копии введите пароль точно, включая пробелы.
3. Посмотрите количество проектов и записей в предпросмотре.
4. Выберите способ восстановления:
   - **«Объединить»** — оставить текущие данные и добавить новые. Если у одной
     записи в двух копиях разное содержимое, приложение остановит импорт,
     а не выберет вариант за вас.
   - **«Заменить»** — удалить текущие проекты и историю и загрузить данные из
     копии вместе с переносимыми настройками. **Сначала сохраните отдельную
     проверенную копию: успешную замену нельзя отменить.**

Если импорт прервался до сохранения базы, изменения откатятся. Если данные уже
восстановлены, а часть настроек ещё ожидает применения, приложение предупредит
об этом и повторит применение настроек. Не запускайте «Заменить» ещё раз только
ради такого предупреждения. Пока скрытие названий не применилось полностью,
проверьте, что показывают уведомление и виджет.

Предпросмотр можно временно скрыть и открыть через «Продолжить восстановление».
«Отменить восстановление» его закроет окончательно. Если Android завершит
процесс приложения, файл придётся выбрать заново. Сам по себе импорт не
запускает таймер.

Системное окно сохранения может предложить облачное хранилище. В таком случае
файл отправляет выбранный сервис. Если после ошибки остался недописанный файл,
удалите именно его — очищать данные приложения не нужно.

### Уведомление, виджет и плитка

Управление доступно в «Настройки → Android и устройство».

- **Уведомление** позволяет поставить таймер на паузу, продолжить или завершить
  сессию. Если уведомления запрещены, откройте их системные настройки; внутри
  приложения таймер по-прежнему доступен.
- **Виджет** можно добавить кнопкой в приложении, если это поддерживает ваш
  рабочий стол. Иначе найдите OTM в обычном списке виджетов.
- **Плитка быстрых настроек** добавляется через системный запрос на Android 13+.
  На Android 12 откройте редактор быстрых настроек и добавьте её вручную.
- **«Скрывать названия проектов»** в разделе «Учёт и проекты» убирает имена из этих системных элементов.
  Это настройка отображения, а не шифрование.

Подпись **«Сегодня … из …»** и полоса прогресса сравнивают время за текущий
день по всем проектам с дневной целью. К закрытым интервалам добавляется
текущий рабочий отрезок, измеренный монотонным таймером устройства. Хронометр
рядом показывает длительность текущей сессии — эти значения могут различаться.
На паузе открытый отрезок больше не растёт. Полоса заполняется до цели,
а подпись продолжает показывать фактически учтённое время и сверх неё.

На Android 16 (API 36) используется системный сегментированный прогресс
`ProgressStyle`; на Android 12–15 (API 31–35) — обычная полоса. Чтобы увидеть
подробности и кнопки, раскройте уведомление. Если системная кнопка выглядит
устаревшей, откройте приложение и проверьте состояние таймера.

Скрытие названий проектов оставляет дневную сумму и цель в обычном уведомлении.
Для показа без личных подробностей приложение предоставляет Android публичную
версию только со статусом таймера. Что появится на экране блокировки,
определяют настройки Android.

### Live Updates и HyperIsland

В «Настройки → Android и устройство» переключатель **«Live Updates и HyperIsland»**
выключен по умолчанию. Он доступен на Android 16 или при обнаружении протокола
острова HyperOS 3 на Xiaomi/Redmi/POCO, в том числе на Android 15. Обнаружение
протокола показывает возможности прошивки, а не допуск OTM производителем.
Обычное уведомление, виджет и плитка остаются доступны на Android 12+.

На Android 16 OTM запрашивает продвижение уведомления только для работающего
таймера. Новый API запроса зависит от обновления системы 36.1; ранняя сборка
Android 16 может оставить обычное уведомление. Это общий путь для Pixel,
Samsung Now Bar и OPPO/OnePlus Live Alerts на поддерживающих сборках. Правила
Android требуют активного действия пользователя, за состоянием которого нужно
следить; рабочий секундомер не получает автоматического допуска. Вид и доступность
решают Android и производитель. realme и vivo не проверены, отдельных интеграций
для них нет. Сегментированный прогресс обычного уведомления Android 16 не зависит
от этого переключателя.
[Правила Android](https://developer.android.com/develop/ui/views/notifications/live-update) ·
[Общие OEM-поверхности](https://blog.google/products-and-platforms/platforms/android/android-16/)

Для HyperOS 3 подготовлен **экспериментальный локальный клиент** по опубликованному
протоколу Xiaomi. Ещё не получены регистрация сервиса, app ID, привязка сертификатов
подписи и согласование сценария. Физического тестирования HyperOS не было,
поэтому включение опции не означает, что остров появится или корректно покажет время.
[Подключение Xiaomi](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)

Клиент запрашивает следующие системные шаблоны:

| Состояние поверхности | Подготовленное содержимое |
| --- | --- |
| Широкая компактная капсула | Значок и время текущей сессии |
| Миниатюрный слот | Только значок |
| Раскрытая карточка | Время сессии, текст дневного итога и две кнопки: «Пауза» или «Продолжить», «Завершить» |

Раскрытый шаблон содержит текст дневного прогресса без отдельной полосы.
Во время работы запрашивается системный отсчёт, на паузе передаётся зафиксированное
время. Воспроизведение накопленной длительности и продолжение отсчёта на реальном
HyperOS ещё не проверены. Срок острова оценивается по сохранённому UTC-началу всей
записи, включая паузы, без продления при повторной публикации. В последние неполные
60 секунд до 12 часов клиент уже оставляет обычное уведомление. Неизвестное или
будущее начало тоже оставляет обычный вид. Изменение системных часов влияет на эту
оценку; фактический OEM timeout не проверен. Ограничение не останавливает таймер.
[Шаблоны и клиентский протокол Xiaomi](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131) ·
[Условия сценария](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2140)

Названия проектов, заметки и идентификаторы записей в JSON острова не попадают.
При блокировке или выключении экрана OTM пересобирает уведомление без OEM-данных;
одного включения экрана недостаточно для их возврата: OTM ожидает разблокировку.
Для AOD подготовлен только статус, без времени и дневного итога. Это поведение
клиента, а не подтверждение защиты всех поверхностей прошивки: обработка Xiaomi
публичной версии уведомления и фактический вид на заблокированном экране не проверены.
Подробнее — в [описании приватности](SECURITY.md#уведомления-и-фоновые-функции).

Если вы скрыли уведомление, запросы Android и HyperIsland для той же записи
не повторяются, в том числе после паузы и продолжения. Новая запись снова подходит
для запроса; чтобы явно сбросить скрытие текущей, выключите и включите опцию.
Выбор хранится только на устройстве и не входит в копии. Остров может отсутствовать
из-за допуска производителя; отключать защиту батареи ради него не требуется.

### Если таймер требует проверки

Закрытие экрана само по себе не завершает сессию. Но после перезагрузки или
потери надёжных данных о времени приложение может не знать, сколько вы работали.

Откройте таймер и разберите запись с пометкой «Нужна проверка». Можно оставить
только подтверждённое время или указать окончание, которое вы можете проверить.
Приложение не добавляет неизвестные часы автоматически.

### Тема, язык и настройки

На главной странице настроек показан компактный список разделов с поиском.
Введите название настройки, действия или ключевое слово, чтобы найти нужный раздел.
Нажмите результат, чтобы открыть его — значения настроек от этого не меняются.
Если совпадений нет, измените запрос или очистите
его, чтобы снова увидеть весь список. После возврата из раздела и восстановления
экрана сохраняются запрос и место прокрутки обзора.

При открытой экранной клавиатуре нижняя навигация временно скрывается, чтобы
оставить место для ввода и результатов. При вводе список удерживает строку поиска
вверху видимой области. Закройте клавиатуру или откройте результат — панель
навигации вернётся.

В «Оформлении» доступны системная, светлая и тёмная темы, цвета устройства и язык.
На Android 13+ язык также можно выбрать в системных настройках приложения;
на Android 12 используйте выбор внутри OTM.

«Учёт и проекты» содержит проект по умолчанию, дневную цель и скрытие названий
проектов. После «Копий данных» расположен раздел «Android и устройство»:
уведомления, виджет, плитка, состояние ограничений и «Live Updates и HyperIsland».
Сведения «Совместимость и работа в фоне» в нём изначально свёрнуты; кнопки
добавления виджета и плитки, системные настройки приложения и действующие
предупреждения видны сразу. Справка и сведения о приложении остаются отдельными
разделами. Размер окна и крупный шрифт могут менять
расположение элементов: на узком экране с крупным шрифтом кнопки навигации
располагаются в два ряда, а содержимое длинных диалогов прокручивается.
Черновики обычных форм и заметки текущей сессии сохраняются при пересоздании
экрана и изменении компоновки. Пароли резервных копий не сохраняются.

### Если не обновляются системные кнопки

Сначала откройте приложение, проверьте уведомления и его страницу в настройках
Android. В разделе «Android и устройство» видны признаки экономии батареи и фоновых
ограничений — приложение показывает их, но не меняет само.

- На **HyperOS/MIUI** проверьте уведомления, батарею и, если есть, автозапуск.
- На **One UI** посмотрите списки спящих и глубоко спящих приложений.
- На **ColorOS/OxygenOS/realme UI** проверьте ограничения фоновой активности.

Названия меню зависят от модели и версии прошивки. Меняйте только то, что
помогает решить конкретную проблему; заранее отключать всю защиту батареи
не нужно. Это общие подсказки, а не список протестированных устройств.
Измерений экономии батареи для приложения пока нет.

### Безопасное обновление

Сохраните и проверьте зашифрованную копию, затем установите APK нового релиза
поверх старого. Для обновления нужна совместимая подпись. В 1.1.0 сохранён
ключ версии 1.0.0; тестовая сборка может использовать другой ключ.
При ошибке подписи не удаляйте приложение и не очищайте данные ради установки.

У стабильного релиза 1.1.0 файл называется `Open-TimeManager-1.1.0.apk`.
Текущие локальные и CI-сборки создают `OTM.apk`, а на устройстве приложение
называется **OTM**. Идентификатор приложения не изменён; короткое имя APK
само по себе не меняет совместимость подписи. Для текущей ветки `main` см.
[инструкцию по сборке](BUILDING.md).

[Скачать релиз](https://github.com/AnoY606-git/Open-TimeManager/releases/latest) ·
[Подробнее о защите данных](SECURITY.md)

## English

This guide covers the current `main` branch of OTM (Open TimeManager) after
1.1.0. First-run setup, monthly plans, settings-section search, updated daily notification progress,
and the experimental HyperIsland client are currently available in development builds. The latest
stable release is 1.1.0; see its
[release notes](releases/1.1.0.md#english) for that version's features.
Searchable help is also available offline under Settings → How to use the app.

### First-run setup

A fresh OTM installation offers an optional setup wizard: Android and device-family
information, notification and background-restriction status, then access to
optional system features. The manufacturer is not presented as a confirmed ROM
name; the wizard clearly says when firmware identification is unavailable.

Notification permission is requested only after you tap its button. The wizard
does not enable autostart, remove battery restrictions or opt into Live Updates
on its own. Status is checked again after returning from system settings.

You can skip at any step. Skip, system Back or completion is remembered on this
device, so the wizard does not reappear on the next launch. Existing installations
are not interrupted automatically after an update. Reopen it manually under
Settings → Android and device. This local choice is excluded from backups.
Skipping does not prevent in-app time tracking.

### Your first work session

1. Open Timer and choose a project, or leave No project selected.
2. Tap Start.
3. Use Pause for a break, then Resume.
4. Tap Stop when you finish. The entry will appear in History.

You can add a note to the current session; remember to save it in the editor.
The daily goal is a progress target, not an alarm or an automatic stop.
Only one timer runs at a time. Switching projects asks for confirmation,
finishes the old entry, and starts a new one.

### Projects

Create a project on Timer and give it a name and color. Archive a finished project
to hide it from new work without deleting its history. First finish or resolve
any active session that uses it. No project is always available and cannot be archived.

Choose a **default project** under Settings → Tracking and projects. It is
suggested for new work but never changes an active session or overrides a choice
you made on the open Timer screen. Missing or archived projects are ignored;
Automatic selection clears the preference.

The widget and tile can start the default project immediately. Without a default,
quick Start works only when exactly one project is available; otherwise the app
opens for you to choose. This preference stays on the device, outside backups.

### History and manual entries

Search History by project or note. Add entry lets you record work you did not
time: choose a local date, start time, duration, and project. Load more opens
older entries; the project picker has its own search.

Changing a note or project preserves the original timing. Changing the time
replaces the old intervals with the duration you enter. You can keep an existing
entry's archived project. Confirmed deletion cannot be undone.
If the app says an entry has changed, reopen it rather than overwriting newer data.

### Reports and CSV

Choose a week, month, or custom range of up to 10 years. Period boundaries follow
your current time zone. Reports include confirmed, closed intervals; a running
interval is added after you pause, stop, or switch projects. That is why the live
timer can show more than the report.

In monthly mode, use the arrows to browse calendar months or jump to the last
or current month. Set planned hours and minutes for that specific month in
Monthly balance. The card shows confirmed time minus the plan: `+` means time
above plan, while `−` means a shortfall for a finished month or time remaining
for the current month. For example, 152 hours against a 160-hour plan gives
`−08:00:00`. An unset plan is not treated as missing work, and a zero-hour plan
is allowed. Remove plan returns the month to having no plan.

The weekday estimate uses Monday–Friday and your current daily goal, not public
holidays, leave, or individual schedules. It only fills the form when requested;
review and save it yourself. Changing the daily goal does not change saved
monthly plans. Plans stay on this device, outside backups; restoring time
records does not replace them.

Export saves the chosen range as CSV through Android's file picker.
**CSV is for spreadsheets, not restoring the app.** It can contain project names
and notes, so keep it private.

### Backups and restore

#### Save a copy

1. Pause or stop first. The current open work interval is not included.
2. Open Settings → Backups and restore.
3. Choose an encrypted backup, use a long, unique passphrase, and save it somewhere
   separate from your device.
4. To check the file, open it through restore and review the preview. You do not
   need to apply the restore.

| Format | When to use it |
| --- | --- |
| **`.otmbackup` — password-protected** | The recommended default. Contents are encrypted. A forgotten password cannot be recovered. |
| **`.otmplain` — no password** | Only when you need a readable file and accept the risk. Anyone with it can read and alter the contents. |

New passwords need at least 12 Unicode code points and at most 1024 UTF-8 bytes.
Spaces matter; older copies with shorter passwords still work. If an encrypted
file does not open, check the password and file. The app does not bypass encryption.

Copies include projects, notes, saved intervals, theme, device colors, hidden
project labels, and the daily goal. Language, monthly plans, the default-project
choice, and the Live Updates and HyperIsland preference stay on the device,
outside backup files.
Older encrypted copies remain supported. The app accepts up to
4 MiB and 20,000 projects, entries, and intervals of each kind.

Unprotected copies have separate warnings before creation and inspection.
Their checksum can detect accidental damage, **not who created the file or
whether someone deliberately changed it**. Only open copies from trusted sources.

#### Restore a copy

1. Finish the timer. Restore is also blocked while paused or awaiting review.
2. Choose the file. For an encrypted copy, enter its exact password, including spaces.
3. Check the project and entry counts in the preview.
4. Choose a restore mode:
   - **Merge** keeps current data and adds new records. If the same record has
     different contents in the two copies, import stops rather than guessing.
   - **Replace** removes current projects and history, then loads the copy and its
     portable settings. **Save and check a separate backup first: a successful
     replacement cannot be undone.**

If import fails before the database is committed, changes roll back. If records
are already restored but some settings still need applying, the app warns and
retries those settings. Do not repeat Replace just for that warning. Until hidden
project labels are fully applied, check what the notification and widget display.

Hide for now keeps the preview available through Resume restore preview.
Cancel restore discards it. If Android ends the app process, choose the file
again. Import never starts a timer automatically.

Choosing a cloud location in the file picker lets that service upload the file.
If an error leaves an incomplete file behind, delete that file, not the app's data.

### Notification, widget, and Quick Settings

Find system controls under Settings → Android and device.

- **Notification:** pause, resume, or stop the session. If notifications are blocked,
  open their system settings. You can still use the timer inside the app.
- **Widget:** use the in-app add button if your launcher supports it, or find
  OTM in the launcher's widget list.
- **Quick Settings tile:** Android 13+ can show a system add dialog. On Android 12,
  add the tile manually through the Quick Settings editor.
- **Hide project names:** under Tracking and projects, removes labels from these controls; it does not encrypt data.

**Today … of …** and the progress bar compare today's tracked time across all
projects with the daily goal. The current open work interval is added to closed
intervals using the device's monotonic clock. The chronometer shows the current
session's duration, so these values can differ. Pausing stops the open interval
from growing. The bar fills up to the goal; the label still shows actual time
after the goal is exceeded.

Android 16 (API 36) uses native segmented `ProgressStyle`; Android 12–15
(API 31–35) uses a standard progress bar. Expand the notification to see details
and controls. If a system control looks out of date, reopen the app and check
the timer's state.

Hiding project names leaves daily totals and the goal in the normal notification.
The app provides Android with a public version containing only the timer status.
Android settings decide what appears on the lock screen.

### Live Updates and HyperIsland

The **Live Updates and HyperIsland** switch under Settings → Android and device
is off by default. It is available on Android 16 or when the HyperOS 3 island
protocol is detected on Xiaomi/Redmi/POCO, including Android 15. Protocol detection
reports firmware capability, not manufacturer approval for OTM. The ordinary
notification, widget, and tile remain available on Android 12+.

On Android 16, OTM requests notification promotion only while the timer is running.
The newer request API depends on system update 36.1; an earlier Android 16 build
may keep the ordinary notification. This is the shared route for Pixel, Samsung
Now Bar, and OPPO/OnePlus Live Alerts on supporting builds. Android's criteria
require an active user-initiated activity whose state needs monitoring; a work
stopwatch is not automatically eligible. Android and the manufacturer decide
availability and appearance. realme and vivo are unverified, with no separate
integrations. Android 16's ordinary segmented progress does not depend on this switch.
[Android criteria](https://developer.android.com/develop/ui/views/notifications/live-update) ·
[Shared OEM surfaces](https://blog.google/products-and-platforms/platforms/android/android-16/)

An **experimental local client** is prepared for HyperOS 3 using Xiaomi's published
protocol. Service registration, an app ID, signing-certificate registration, and
scenario approval have not been obtained. There has been no physical HyperOS
testing, so enabling the switch does not mean an island will appear or display
the correct time.
[Xiaomi onboarding](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)

The client requests these system templates:

| Surface | Prepared content |
| --- | --- |
| Wide compact pill | An icon and the current session's elapsed time |
| Mini slot | An icon only |
| Expanded card | Session time, a daily-total text, and two buttons: Pause or Resume, and Stop |

The expanded template uses daily-progress text without a separate progress bar.
Running uses a requested system chronometer; paused time is a fixed value.
Accumulated duration and resumed timing have not been validated on a physical
HyperOS device. Island lifetime is estimated from the saved UTC start of the entire
entry, including pauses, without extension on repost. During the final incomplete
60 seconds before 12 hours, or when the start is unknown or in the future, the
client keeps only the ordinary notification. System-clock changes affect this
estimate; actual OEM timeout behavior is unverified. This does not stop tracking.
[Xiaomi templates and client protocol](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131) ·
[Scenario criteria](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2140)

Project names, notes, and record identifiers never enter the island JSON. On lock
or screen-off, OTM rebuilds the notification without OEM data. Turning the screen
on alone does not restore it: OTM waits for unlock. Its prepared AOD
content is status only, without time or daily totals. This describes the client,
not certified protection on every firmware surface: Xiaomi's handling of the
public notification version and actual locked-screen behavior are unverified.
See [privacy details](SECURITY.md#system-controls-and-reporting).

Dismissing the notification suppresses both Android and HyperIsland requests for
that entry, including after pause and resume. A new entry can request them again;
to explicitly reset the current dismissal, turn the option off and on. This choice
stays on the device, outside backups. Manufacturer approval may prevent an island
from appearing; disabling battery protections is not required.

### When a timer needs review

Closing the screen does not itself stop a session. After a reboot or loss of
reliable timing data, however, the app may not know how long you worked.

Open the entry marked Review required. Keep only confirmed time or provide an
end time you can verify. The app does not add unknown hours automatically.

### Appearance and language

The settings overview uses compact section rows and a search field. Enter a
setting name, action label or keyword to find the right page. Tap a result to
open its section; this does not change preference values. If nothing matches, edit or clear the query to return
to all sections. The query and overview scroll position survive returning from
a section and screen restoration.

While the software keyboard is open, bottom navigation temporarily hides to
leave room for input and results. Typing keeps the search field at the top of
the visible area. Close the keyboard or open a result to bring navigation back.

Appearance offers system, light, and dark themes, device colors, and language.
On Android 13+, language is also available in Android's app settings. On Android 12,
use the in-app selector. Tracking and projects keeps the default project, daily
goal, and hidden project labels. Android and device follows Backups and restore;
it contains notifications, the widget, the tile, restriction status, and Live
Updates and HyperIsland. Compatibility and background activity starts collapsed;
add-widget, add-tile, and app-system-settings buttons and active warnings remain
visible. Help and app information stay in their own sections.
Layout adapts to the window size and larger fonts: narrow
screens with large text use two rows of navigation buttons, and long dialogs
scroll. Ordinary form drafts and the current session's note survive screen
recreation and layout changes. Backup passwords are not saved.

### If system controls stop updating

First open the app, check notification access, and review its Android settings.
Android and device shows Battery Saver and background-restriction status;
the app reads these settings but does not change them.

- **HyperOS/MIUI:** check notifications, battery settings, and autostart if available.
- **One UI:** check Sleeping and Deep sleeping apps.
- **ColorOS/OxygenOS/realme UI:** check background activity restrictions.

Menu names vary. Change only what helps with the specific problem; you do not
need to disable every battery protection. These are general suggestions, not a
list of tested devices. Battery savings have not been measured.

### Update safely

Save and check an encrypted backup, then install the new release over the old
one. Updates need a compatible signature. Version 1.1.0 keeps 1.0.0's signing key;
test builds may use another. Do not uninstall or clear data to force an update
after a signature mismatch.

The stable 1.1.0 APK is named `Open-TimeManager-1.1.0.apk`. Current local and CI
builds produce `OTM.apk` and display **OTM** on the device. The application ID
is unchanged; shortening the APK name does not change signing compatibility.
For the current `main` branch, see [Building from source](BUILDING.md#english).

[Download a release](https://github.com/AnoY606-git/Open-TimeManager/releases/latest) ·
[More about data protection](SECURITY.md#english)
