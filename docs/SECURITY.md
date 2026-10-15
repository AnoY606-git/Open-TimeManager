# Безопасность и приватность / Security and privacy

Open TimeManager — offline-приложение без `INTERNET`, аккаунта, рекламы, аналитики, location, AccessibilityService и широкого file access. Room-база и настройки находятся во внутреннем credential-protected storage. Их защищают Android sandbox и шифрование устройства; Room не заявляется как отдельное app-level шифрование БД.

Системные команды адресуют конкретную запись и revision, поэтому повтор старого PendingIntent не должен менять новый таймер. Компоненты закрыты, кроме launcher Activity и TileService, защищённой системным `BIND_QUICK_SETTINGS_TILE`. Автоматический Android backup/transfer отключён. Явный экспорт использует Storage Access Framework.

## Portable backup

- `OTMB1`: PBKDF2-HMAC-SHA256, 310 000 итераций, salt 16 байт, nonce 12 байт, AES-256-GCM, tag 128 бит;
- весь 42-байтовый заголовок аутентифицируется как AAD;
- неверный пароль и повреждённый ciphertext дают одинаковый результат без provider-specific подробностей;
- UI очищает пароль и собственные временные буферы, но JVM/provider могут создавать внутренние копии;
- Android-путь ограничен 4 МиБ и 20 000 объектов каждого вида; hard limit core-формата — 50 МиБ и 200 000;
- импорт не запускает активную сессию автоматически.

Криптографический дизайн имеет golden vector и тесты tamper/wrong-password, но независимый аудит не проводился. Храните пароль отдельно от копии.

## Риски операций

Replace — разрушительная операция: успешное подтверждение заменяет всю локальную историю без Undo. SQLite-транзакция исключает частичную замену при сбое, но не отменяет успешное действие. Сначала создайте и проверьте отдельную копию. Restore блокируется при RUNNING, PAUSED и NEEDS_REVIEW.

CSV не зашифрован и может содержать имена проектов и заметки. Формульные префиксы обезвреживаются, однако CSV остаётся чувствительным документом и не подходит для полного восстановления.

## English summary

The app requests no network, location, broad-storage, advertising, or analytics capability. Local data relies on Android internal-storage and device-encryption guarantees; portable backups receive separate app-level encryption. Automatic platform backup is disabled. System actions carry entry identity and revision to reject stale replays.

Replace is intentionally destructive after confirmation. Transaction rollback prevents partial replacement, not regret after success. Keep a separately verified backup. CSV is plaintext and sensitive. No independent security or cryptographic audit is claimed. Report vulnerabilities privately through [SECURITY.md](../SECURITY.md).
