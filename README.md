# AlopetsiyaInventory

Серверный мод для NeoForge 1.21.1: снимает инвентарь игрока и отправляет его
на сайт Alopetsiyacraft (страница «Инвентарь»).

## Как работает

Снимок делается при:

- выходе игрока с сервера (`PlayerLoggedOutEvent`);
- входе игрока (`PlayerLoggedInEvent`) — чтобы данные появились, даже если игрок
  никогда не вышел «нормально»;
- остановке сервера (`ServerStoppingEvent`) — для тех, кто ещё онлайн.

Снимок уходит в `POST {websiteUrl}/api/inventory/from-server` с заголовком
`x-api-key`. В нём:

- `containers` — четыре контейнера фиксированной длины, пустой слот — `null`:
  - `main` — 36 слотов рюкзака,
  - `armor` — 4 слота брони (0 — ботинки … 3 — шлем),
  - `offhand` — 1 слот,
  - `enderChest` — 27 слотов эндер-сундука;
- каждый предмет — `{ id, count, name, damage?, maxDamage?, enchantments?, lore? }`
  (`enchantments` — массив `{ id, lvl }`);
- здоровье игрока и еда: `xpLevel`, `health`, `healthMax`, `food`, `saturation`;
- `updatedAt` — время снимка (мс).

## Конфиг

`config/alopetsiyainventory-common.toml`:

```toml
[alopetsiyainventory]
websiteUrl = "http://127.0.0.1:3000"
chatApiKey = "change-me-server-chat-key"
```

`chatApiKey` должен совпадать с `CHAT_API_KEY` в `.env` сайта.

## Сборка

```bash
./gradlew build
```

Готовый jar лежит в `build/libs/` — его нужно положить в папку `mods` сервера.
Мод только серверный, на клиент ставить не нужно.