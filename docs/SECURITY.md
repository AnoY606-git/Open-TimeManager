# Безопасность и приватность / Security and privacy

Open TimeManager — offline-приложение без `INTERNET`, аккаунта, рекламы, аналитики, location, AccessibilityService и широкого file access. Room-база и настройки находятся во внутреннем credential-protected storage. Их защищают Android sandbox и шифрование устройства; Room не заявляется как отдельное app-level шифрование БД.

Системные команды адресуют конкретную запись, revision и поколение слота, поэтому повтор старого PendingIntent не должен менять новый таймер даже после исчерпания `Long.MAX_VALUE` и безопасного перевыпуска слота. Компоненты закрыты, кроме launcher Activity и TileService, защищённой системным `BIND_QUICK_SETTINGS_TILE`. Автоматический Android backup/transfer отключён. Явный экспорт использует Storage Access Framework.

## Portable backup

- `OTMB1`: PBKDF2-HMAC-SHA256, 310 000 итераций, salt 16 байт, nonce 12 байт, AES-256-GCM, tag 128 бит;
- весь 42-байтовый заголовок аутентифицируется как AAD;
- неверный пароль и повреждённый ciphertext дают одинаковый результат без provider-specific подробностей;
- UI очищает пароль и собственные временные буферы, но JVM/provider могут создавать внутренние копии;
- Android-путь ограничен 4 МиБ и 20 000 объектов каждого вида; hard limit core-формата — 50 МиБ и 200 000;
- импорт не запускает активную сессию автоматически;
- payload schema v2 сохраняет метаданные проектов и переносимые настройки; schema v1 читается с безопасными значениями по умолчанию;
- длительность каждой записи проверяется точным сложением сегментов без переполнения.

Криптографический дизайн имеет golden vector и тесты tamper/wrong-password, но независимый аудит не проводился. Храните пароль отдельно от копии.

## Риски операций

Replace — разрушительная операция: успешное подтверждение заменяет всю локальную историю без Undo. Suspendable SQLite-транзакция и контрольные точки между ограниченными пакетами исключают частичную замену при сбое или отмене, но не отменяют уже подтверждённое действие. Предпросмотр расходуется только после результата Applied и остаётся пригоден для повторной попытки после блокировки, конфликта, отмены или отката. Сначала создайте и проверьте отдельную копию. Restore блокируется при RUNNING, PAUSED и NEEDS_REVIEW.

Настройки находятся в DataStore, а предметные данные — в Room, поэтому общая атомарная транзакция между ними не заявляется. Одна Room-транзакция записывает восстановленные данные и намерение применить настройки; после коммита устойчивый журнал доводит DataStore до согласованного состояния и повторяется при следующем запуске, если процесс был прерван. Ручные изменения переносимых настроек проходят через ту же последовательную границу: сначала завершается более старое намерение, затем новая полная настройка сама сохраняется как повторяемое намерение до успешной записи DataStore и privacy mirror.

CSV не зашифрован и может содержать имена проектов и заметки. Формульные префиксы обезвреживаются, однако CSV остаётся чувствительным документом и не подходит для полного восстановления.

## English summary

The app requests no network, location, broad-storage, advertising, or analytics capability. Local data relies on Android internal-storage and device-encryption guarantees; portable backups receive separate app-level encryption. Automatic platform backup is disabled. System actions carry entry identity, revision, and slot generation to reject stale replays, including after revision exhaustion.

Replace is intentionally destructive after confirmation. Room transaction rollback prevents partial replacement on failure or cancellation, not regret after success. Portable preferences converge through a durable post-commit journal; one atomic transaction across Room and DataStore is not claimed. Keep a separately verified backup. CSV is plaintext and sensitive. No independent security or cryptographic audit is claimed. Report vulnerabilities privately through [SECURITY.md](../SECURITY.md).
