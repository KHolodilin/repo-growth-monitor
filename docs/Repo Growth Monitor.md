# Repo Growth Monitor
## Техническое задание

**Repository:** `repo-growth-monitor`  
**Product name:** Repo Growth Monitor  
**Backend package:** `com.kholodilin.repogrowth`

**Версии документа:** v0.1 Data Foundation + v0.2 GitHub Search Tracking

---

# 1. Назначение продукта

**Repo Growth Monitor** — self-hosted Web-приложение для мониторинга и анализа продвижения GitHub-репозиториев.

Система должна позволять владельцу нескольких GitHub-проектов понимать:

- сколько трафика получают repositories;
- как traffic меняется во времени;
- откуда приходят пользователи;
- какие repositories растут быстрее;
- по каким GitHub Search Queries находится repository;
- на каком месте repository находится в GitHub Search;
- растёт или падает его поисковая позиция;
- какие repositories находятся рядом с ним в выдаче;
- как меняются stars и forks.

В следующих версиях система должна позволять анализировать конкурентов и связывать изменение показателей с действиями по продвижению:

- README changes;
- Issues;
- Releases;
- LinkedIn posts;
- Reddit posts;
- статьи;
- другие Growth Events.

Главный продуктовый вопрос:

> **What makes my GitHub repositories grow?**

---

# 2. Цели

## 2.1. Пользовательские цели

Пользователь должен иметь возможность:

1. Запустить приложение через Docker Compose.
2. Подключить GitHub через Fine-grained PAT.
3. Получить список доступных repositories.
4. Выбрать repositories для мониторинга.
5. Накапливать GitHub Traffic history.
6. Просматривать статистику всего portfolio.
7. Просматривать статистику конкретного repository.
8. Создавать Search Queries для repository.
9. Отслеживать позицию repository в GitHub Search.
10. Просматривать историю изменения позиции.
11. Просматривать Top-N GitHub Search Results.
12. В дальнейшем сравнивать repository с конкурентами.
13. Анализировать влияние Growth Events на продвижение.

---

# 3. Product Positioning

> **Self-hosted GitHub repository growth analytics: traffic, search rankings, competitors and promotion impact.**

Продукт не должен быть только архиватором GitHub Traffic.

Целевая модель:

```text
Actions
   ↓
Search Visibility
   ↓
Traffic
   ↓
Clones / Stars
   ↓
Competitive Position
```

---

# 4. Scope

## v0.1 — Data Foundation

Реализовать:

- GitHub integration;
- repository discovery;
- repository tracking;
- Traffic collection;
- Views;
- Unique Visitors;
- Clones;
- Unique Cloners;
- Referrers;
- Popular Paths;
- Stars history;
- Forks history;
- repository metadata;
- historical storage;
- Portfolio Dashboard;
- Repository Details;
- automatic collection;
- manual collection;
- PostgreSQL;
- Web UI;
- Docker deployment;
- observability.

## v0.2 — GitHub Search Tracking

Реализовать:

- Search Queries;
- полный GitHub Search query;
- daily search tracking;
- Top-N Search Results;
- default Top-50;
- repository rank;
- rank history;
- 7d/30d rank change;
- best rank;
- Search Visibility page;
- Search Results page;
- foundation для competitor discovery.

---

# 5. Future Scope

Краткий roadmap:

### v0.3 — Competitor Tracking

- manual competitors;
- automatic discovery;
- competitor search ranking;
- Stars history;
- Star Velocity;
- comparison.

### v0.4 — Growth Events

Автоматические:

- README change;
- Release;
- Issue;
- PR;
- Tag;
- Contributor.

Ручные:

- LinkedIn;
- Reddit;
- Hacker News;
- Habr;
- Medium;
- Dev.to;
- Telegram;
- YouTube;
- Article;
- Custom Event.

### v0.5 — Impact Analysis

- Before / After;
- traffic changes;
- search position changes;
- referrer changes;
- stars/clones changes;
- comparison of promotion activities.

Система должна показывать correlation, но не утверждать causation без достаточных данных.

### v0.6 — Insights & Alerts

- traffic spikes;
- ranking changes;
- unusual growth;
- competitor overtaking;
- new referrers;
- automated insights.

### v0.7 — Public Widgets

- repository badges;
- growth badges;
- GitHub Profile Top-N;
- Trending repositories;
- website widgets.

### v1.0

- GitHub App;
- OAuth;
- multi-account;
- multi-user;
- authentication;
- SaaS readiness;
- webhooks;
- public/private dashboards.

---

# 6. Общая архитектура

Приложение реализуется как **Modular Monolith**.

```text
                         GitHub API
                              │
                              ▼
┌─────────────────────────────────────────────────┐
│             Repo Growth Monitor                │
│                                                 │
│  Spring Boot                                   │
│                                                 │
│  ├── GitHub Integration                        │
│  ├── Repository                                │
│  ├── Traffic                                   │
│  ├── Search                                    │
│  ├── Analytics                                 │
│  ├── Collection Planner                        │
│  ├── Collection Workers                        │
│  ├── Search Planner                            │
│  ├── Search Workers                            │
│  └── React static frontend                     │
│                                                 │
└───────────────────────┬─────────────────────────┘
                        │
                        ▼
                   PostgreSQL
```

Kafka, RabbitMQ, Redis и другие инфраструктурные компоненты для v0.1/v0.2 не требуются.

PostgreSQL используется одновременно как:

- основное persistent storage;
- durable job queue;
- coordination mechanism для workers.

---

# 7. Backend Stack

- Java 21;
- Spring Boot 4.1.x;
- Maven;
- Spring MVC;
- Spring JDBC;
- `JdbcClient`;
- PostgreSQL;
- Liquibase;
- Lombok;
- Jackson;
- OpenAPI / Swagger;
- Spring Boot Actuator;
- Micrometer.

Не используются:

- JPA/Hibernate;
- MapStruct.

---

# 8. Frontend Stack

- React;
- TypeScript;
- Vite;
- shadcn/ui;
- Apache ECharts.

Apache ECharts используется для:

- traffic time-series;
- search ranking;
- repository comparison;
- future event annotations;
- before/after visualization;
- long time ranges;
- zoom;
- multiple series.

---

# 9. Source Structure

```text
repo-growth-monitor/

├── backend/
│   ├── src/
│   └── pom.xml
│
├── frontend/
│   ├── src/
│   └── package.json
│
├── Dockerfile
├── docker-compose.yml
└── README.md
```

Backend root package:

```text
com.kholodilin.repogrowth
```

Пример package structure:

```text
com.kholodilin.repogrowth

├── github
│   ├── client
│   ├── configuration
│   ├── model
│   └── exception
│
├── repository
│   ├── api
│   ├── application
│   ├── domain
│   └── persistence
│
├── traffic
│   ├── api
│   ├── application
│   ├── domain
│   └── persistence
│
├── search
│   ├── api
│   ├── application
│   ├── domain
│   └── persistence
│
├── collection
│   ├── planner
│   ├── worker
│   ├── collector
│   └── persistence
│
├── analytics
│
├── competitor
│
├── event
│
└── common
```

---

# 10. Production Packaging

Frontend и backend разрабатываются отдельно.

Production frontend:

```text
React
  ↓
Vite build
  ↓
dist/
  ↓
Spring Boot static resources
```

Итоговый application Docker image содержит:

```text
Spring Boot Backend
+
React Web UI
```

Frontend не запускается отдельным production container.

---

# 11. Docker Deployment

Минимальный deployment:

```text
Docker Compose

├── repo-growth-monitor
└── postgres
```

Запуск:

```bash
docker compose up -d
```

После запуска:

```text
http://localhost:8080
```

Пользователь не должен устанавливать:

- Java;
- Node.js;
- PostgreSQL;
- frontend tooling.

Deployment должен одинаково работать локально и на VPS.

---

# 12. GitHub Authentication

v0.1 использует:

**Fine-grained Personal Access Token.**

Token передаётся через:

- environment variable;
- Docker environment;
- Docker secret.

Например:

```text
GITHUB_TOKEN=github_pat_xxx
```

Token:

- не хранится в PostgreSQL;
- не возвращается через REST;
- не выводится в лог;
- должен маскироваться в diagnostics.

OAuth/GitHub App относятся к future roadmap.

---

# 13. Repository Types

Поддерживаются:

1. Personal Public repositories.
2. Personal Private repositories.
3. Organization repositories, доступные PAT.

Модель данных не должна предполагать, что repository owner всегда является текущим GitHub user.

---

# 14. GitHub Owner

Сущность:

```text
github_owner
```

Поля:

```text
id
github_id
login
owner_type
avatar_url
html_url
created_at
updated_at
```

Тип:

```text
USER
ORGANIZATION
```

---

# 15. Repository

Минимальная модель:

```text
repository

id
github_id
owner_id

name
full_name
description

visibility
default_branch

language

fork
archived

stars
forks
open_issues

tracking_enabled

github_created_at
github_updated_at

created_at
updated_at
```

`github_id` должен иметь unique constraint.

---

# 16. Repository Discovery

Backend получает repositories, доступные GitHub Token.

UI:

```text
Repositories

[x] spring-transactional-outbox-kafka
[x] spring-boot-outbox-starter
[ ] experiments
[x] spring-boot-state-machine-starter
```

Пользователь включает/отключает tracking.

---

# 17. Collection Orchestration

Collection не должна зависеть от одного запуска cron в определённый момент.

Используется модель:

```text
Planner
   ↓
Persistent Queue
   ↓
Workers
   ↓
Collectors
```

Система должна корректно переживать:

- restart application;
- temporary GitHub failures;
- partial collection;
- worker failure;
- repeated Planner execution.

---

# 18. Strict Collection Window

Collection Planner работает только внутри заданного временного окна.

Default:

```text
10:00–18:00
```

Период проверки:

```text
10 minutes
```

Конфигурация:

```yaml
collection:
  planner:
    from: "10:00"
    to: "18:00"
    interval: 10m
```

Planner каждые 10 минут проверяет наличие необходимых jobs.

---

# 19. Strict Window Policy

Используется политика:

**Strict Window.**

Если приложение было выключено всё collection window и запущено после его окончания:

```text
10:00 ───────── 18:00     21:00
       OFF                  ↑
                         startup
```

новые jobs за текущий день не создаются.

День считается пропущенным.

Catch-up после окончания окна не выполняется.

---

# 20. Historical Data Policy

Запрещается искусственно создавать point-in-time snapshots за даты, когда collection реально не выполнялся.

Например, нельзя задним числом создавать:

- Stars snapshot;
- Forks snapshot;
- Search Rank;
- Referrer snapshot;
- Popular Paths snapshot.

Если GitHub API сам возвращает реальные historical daily values, они могут быть сохранены.

Например:

```text
Views
Clones
```

могут поддерживать historical backfill в пределах данных, реально возвращённых GitHub.

---

# 21. Collection Modes

Collectors логически разделяются на:

```text
HISTORICAL_WINDOW
POINT_IN_TIME
```

### Historical Window

Collector может сохранить реальные значения предыдущих дат, если они присутствуют в GitHub response.

Примеры:

- Views;
- Clones.

### Point In Time

Сохраняется состояние только на момент фактического collection.

Примеры:

- repository stats;
- search rank;
- referrers;
- popular paths.

---

# 22. Collection Planner

Planner:

- не вызывает GitHub API;
- не выполняет collection;
- только определяет отсутствующие jobs;
- создаёт jobs через idempotent insert.

Принцип:

```sql
INSERT ...
ON CONFLICT DO NOTHING
```

Planner может безопасно выполняться многократно.

---

# 23. Repository Collection Run

Для каждого repository/day создаётся:

```text
collection_run
```

Поля:

```text
id
repository_id
business_date

status

planned_jobs
successful_jobs
failed_jobs

created_at
completed_at
```

Статусы:

```text
PLANNED
RUNNING
SUCCESS
PARTIAL
FAILED
```

`collection_run` является агрегированным бизнес-состоянием daily collection.

---

# 24. Collection Job Queue

Repository-level collection использует:

```text
collection_job
```

Поля:

```text
id
collection_run_id
repository_id

job_type
business_date

status

attempt
next_attempt_at

locked_by
locked_until

started_at
completed_at

error_code
error_message

created_at
updated_at
```

Статусы:

```text
READY
RUNNING
RETRY
SUCCESS
FAILED
```

---

# 25. Collection Job Types

v0.1:

```text
TRAFFIC
REFERRERS
POPULAR_PATHS
REPOSITORY_STATS
```

Точное количество GitHub endpoints внутри `TRAFFIC` является implementation detail.

Collector должен иметь достаточно мелкую granularity, чтобы ошибка независимого GitHub API не заставляла повторять несвязанные операции.

---

# 26. Collection Job Uniqueness

Для одного repository не должно существовать нескольких одинаковых daily jobs.

Business key:

```text
repository_id
business_date
job_type
```

Unique constraint должен гарантировать это на уровне PostgreSQL.

---

# 27. Partial Collection

Collection Run не является одной транзакцией.

Пример:

```text
Repo A / 27 Aug

TRAFFIC            SUCCESS
REFERRERS          SUCCESS
POPULAR_PATHS      FAILED
REPOSITORY_STATS   SUCCESS

Run = PARTIAL
```

Успешные jobs не должны повторяться из-за ошибки другого collector.

Retry выполняется только для failed/retryable job.

---

# 28. Worker Model

Workers получают jobs из PostgreSQL queue.

Базовая модель:

```text
collection_job
      ↓
Collection Worker
      ↓
Collector Registry
      ↓
Concrete Collector
```

Worker отвечает за:

- claim;
- locking;
- lifecycle;
- retry;
- error handling.

Collector отвечает за получение и сохранение конкретного типа GitHub data.

---

# 29. Collector Interface

Концептуальный контракт:

```java
interface Collector {

    CollectionType type();

    void collect(CollectionContext context);
}
```

Реализации:

```text
TrafficCollector
ReferrerCollector
PopularPathsCollector
RepositoryStatsCollector
```

Search реализуется отдельным pipeline.

---

# 30. PostgreSQL Queue Processing

Для безопасной конкурентной обработки допускается использование:

```sql
FOR UPDATE SKIP LOCKED
```

Job должен иметь lease:

```text
locked_by
locked_until
```

Если worker/JVM завершается во время выполнения:

```text
RUNNING
   ↓
lease expired
   ↓
job becomes available
```

Job может быть обработан повторно.

Collectors обязаны быть idempotent.

---

# 31. Atomicity

GitHub HTTP call не является частью DB transaction.

Результат конкретного collector сохраняется атомарно вместе с успешным завершением job.

Концептуально:

```text
GitHub call
    ↓

BEGIN

UPSERT collected data

job → SUCCESS

COMMIT
```

Если JVM падает до commit, job может быть выполнен повторно.

UPSERT не должен создавать duplicate data.

---

# 32. Retry Policy

Retry используется только для retryable errors.

Пример backoff:

```text
attempt 1 → +1m
attempt 2 → +5m
attempt 3 → +15m
attempt 4 → +60m
```

Допускается jitter.

Примеры:

```text
timeout
5xx
temporary network error
→ RETRY
```

```text
401
invalid token
invalid search query
repository deleted
→ FAILED
```

Для GitHub Rate Limit `next_attempt_at` должен учитывать rate-limit reset, если такая информация доступна.

---

# 33. Jobs After Collection Window

Strict Window ограничивает **создание новых daily jobs**.

Job, созданный внутри окна, может завершиться после `18:00`.

Пример:

```text
17:55 job created
18:03 job completed
```

Это допустимо.

Retry ранее созданного job также может выполняться после окончания Planner window в рамках установленной retry policy.

---

# 34. Repository-level Concurrency

Одновременно разрешается максимум:

> **1 active GitHub job на repository.**

Например:

```text
Repo A → TRAFFIC       RUNNING
Repo A → REFERRERS     WAIT

Repo B → SEARCH        RUNNING
Repo C → METADATA      RUNNING
```

Параллелизм разрешается между разными repositories.

---

# 35. Repository Lock

Ограничение `1 active job/repository` является общим для:

- Collection Workers;
- Search Workers.

То есть Search и обычный Collector одного repository также не должны одновременно обращаться к GitHub.

Механизм реализации должен использовать PostgreSQL coordination.

Допускаются:

- PostgreSQL advisory locks;
- transaction/row locking;
- другой DB-based mechanism.

Redis для этой задачи не требуется.

---

# 36. Worker Concurrency

Количество Collection Workers configurable.

Например:

```yaml
collection:
  workers: 4
```

Это означает возможность одновременно собирать до четырёх разных repositories.

---

# 37. Search Architecture

GitHub Search является отдельным workload.

Он имеет:

- отдельную очередь;
- отдельный worker pool;
- отдельную retry/rate-limit policy.

Search не использует `collection_job`.

Архитектура:

```text
              Planner
                 │
       ┌─────────┴─────────┐
       ▼                   ▼
Collection Planner     Search Planner
       │                   │
       ▼                   ▼
collection_job          search_run
       │                   │
       ▼                   ▼
Collection Workers     Search Workers
```

---

# 38. Search Query

Для repository пользователь создаёт несколько Search Queries.

Примеры:

```text
transactional outbox

transactional outbox language:java

spring boot outbox

kafka outbox stars:>10
```

Хранится полный query.

Сущность:

```text
search_query
```

Поля:

```text
id
repository_id

name
query

enabled
result_limit

created_at
updated_at
```

Default:

```text
result_limit = 50
```

---

# 39. Search Planner

Search Planner работает в том же strict planning window.

Каждый enabled Search Query является независимой единицей работы.

Пример:

```text
Repo A

Query A → Search Run A
Query B → Search Run B
Query C → Search Run C
```

Ошибка Query B не должна блокировать A/C.

---

# 40. Search Run

Отдельная `search_job` таблица не создаётся.

`search_run` одновременно является:

- durable queue item;
- execution state;
- результатом выполнения Search Query.

Поля:

```text
id
search_query_id
repository_id

business_date

status

attempt
next_attempt_at

locked_by
locked_until

started_at
completed_at

total_count
tracked_repository_position

error_code
error_message

created_at
updated_at
```

Статусы:

```text
READY
RUNNING
RETRY
SUCCESS
FAILED
```

---

# 41. Search Run Uniqueness

Для одного Search Query допускается максимум один scheduled run за business date.

Business key:

```text
search_query_id
business_date
```

Planner использует idempotent insert.

---

# 42. Search Workers

Search имеет отдельный worker pool.

Например:

```yaml
search:
  workers: 1
```

Это позволяет отдельно управлять GitHub Search rate limits.

При этом действует общий repository-level lock.

---

# 43. Search Results

Для каждого Search Run сохраняется Top-N GitHub repositories.

Сущность:

```text
search_result
```

Поля:

```text
id
search_run_id

position

github_repository_id
full_name
owner

stars
forks
language
description

repository_created_at
repository_updated_at
```

Default:

```text
N = 50
```

---

# 44. Tracked Repository Position

После выполнения Search система определяет позицию отслеживаемого repository.

Пример:

```text
#1 competitor-a
#2 competitor-b
...
#7 MY REPOSITORY
...
#50 competitor-x
```

Сохраняется:

```text
tracked_repository_position = 7
```

Если repository отсутствует:

```text
tracked_repository_position = NULL
```

UI:

```text
>50
```

---

# 45. Traffic Storage

```text
traffic_daily

id
repository_id
traffic_date

views
unique_visitors

clones
unique_cloners

created_at
updated_at
```

Unique:

```text
(repository_id, traffic_date)
```

Index:

```text
(repository_id, traffic_date DESC)
```

---

# 46. Repository Stats History

```text
repository_daily_stats

id
repository_id
stat_date

stars
forks
open_issues

created_at
```

Unique:

```text
(repository_id, stat_date)
```

В дальнейшем используется для:

- Star Velocity;
- Fork Growth;
- competitor comparison.

---

# 47. Referrer History

```text
traffic_referrer_snapshot

id
repository_id
snapshot_at

referrer
views
unique_visitors
```

Данные являются snapshot фактического observation time.

Нельзя создавать фиктивные historical snapshots.

---

# 48. Popular Paths History

```text
traffic_path_snapshot

id
repository_id
snapshot_at

path
title

views
unique_visitors
```

---

# 49. Основные таблицы v0.1–v0.2

```text
github_owner

repository

traffic_daily

repository_daily_stats

traffic_referrer_snapshot

traffic_path_snapshot

collection_run

collection_job

search_query

search_run

search_result
```

---

# 50. Database Design Principles

База сразу проектируется с прицелом на:

- traffic;
- search;
- competitors;
- Growth Events;
- Impact Analysis;
- public widgets.

Основные правила:

- `BIGINT` surrogate PK;
- GitHub IDs хранятся отдельно;
- `TIMESTAMPTZ`;
- business unique constraints;
- FK integrity;
- индексы по repository/time;
- idempotent UPSERT;
- schema versioning через Liquibase.

---

# 51. Future Growth Event Model

Будущая сущность:

```text
growth_event

id
repository_id

event_at

category
type

title
description
url

source
external_id

created_at
updated_at
```

Примеры:

```text
GITHUB / RELEASE
GITHUB / ISSUE_CREATED
GITHUB / README_CHANGE

MARKETING / LINKEDIN_POST
MARKETING / REDDIT_POST
MARKETING / ARTICLE
```

DB ENUM для типов Growth Events не используется, чтобы не ограничивать расширяемость.

---

# 52. Future Competitor Model

```text
competitor

id
repository_id

competitor_github_repository_id

source
enabled

created_at
updated_at
```

Source:

```text
MANUAL
SEARCH_DISCOVERY
```

Top-N Search Results должны позволять реализовать automatic competitor discovery без изменения базовой search-модели.

---

# 53. Portfolio Dashboard

Главная страница Web UI должна показывать:

- tracked repositories;
- Views;
- Unique Visitors;
- Clones;
- Unique Cloners;
- Stars.

Периоды:

```text
7d
30d
90d
1y
All
```

Пример:

```text
Repositories       8

Views           4,812
Visitors        2,140
Clones            620
Stars             142
```

---

# 54. Portfolio Repository Table

Пример:

| Repository | Visitors | Views | Clones | Stars |
|---|---:|---:|---:|---:|
| repo-a | 1,200 | 2,500 | 340 | 120 |
| repo-b | 740 | 1,300 | 190 | 71 |

В будущем:

- Growth;
- Star Velocity;
- Search Visibility;
- competitor position.

---

# 55. Repository Details

URL:

```text
/repositories/{repositoryId}
```

Страница содержит:

### Overview

- name;
- owner;
- description;
- visibility;
- GitHub URL;
- stars;
- forks;
- last collection.

### Traffic

- Views;
- Visitors;
- Clones;
- Unique Cloners.

### Charts

- Traffic history;
- Clone history.

### Referrers

- source;
- views;
- unique visitors.

### Popular Paths

- path;
- title;
- views;
- unique visitors.

### Collection Status

Например:

```text
27 Aug 2026

✓ Traffic
✓ Referrers
✗ Popular Paths
✓ Repository Stats

3 / 4 successful
PARTIAL
```

---

# 56. Search Visibility Page

Repository Details получает вкладку:

```text
Search Visibility
```

Пример:

| Search Query | Rank | 7d | 30d | Best |
|---|---:|---:|---:|---:|
| transactional outbox | #7 | ↑3 | ↑12 | #5 |
| spring boot outbox | #4 | ↑1 | ↑8 | #3 |
| kafka outbox | #16 | ↓2 | ↑5 | #11 |

---

# 57. Search Rank Chart

Для каждого Search Query:

```text
#1
#5                 ┌────
#10        ┌───────┘
#15 ───────┘

       Jul      Aug
```

Правила:

- меньший rank лучше;
- #1 визуально сверху;
- отсутствие в Top-N отображается отдельно;
- нельзя интерполировать пропущенные observations как реальные positions.

---

# 58. Search Results Page

Пользователь может открыть конкретную выдачу:

```text
"transactional outbox"

Last checked:
27 Aug 2026

#1 competitor-a
#2 competitor-b
#3 competitor-c
...
#7 MY REPOSITORY
...
#50 competitor-x
```

Для каждой строки:

- repository;
- owner;
- stars;
- forks;
- language;
- current position;
- изменение относительно предыдущего Search Run.

---

# 59. REST API

Base:

```text
/api/v1
```

Пример:

```text
GET    /repositories
GET    /repositories/{id}

POST   /repositories/{id}/tracking

GET    /repositories/{id}/traffic

POST   /repositories/{id}/collect
POST   /collection/plan

GET    /collection-runs/{id}


GET    /repositories/{id}/search-queries

POST   /repositories/{id}/search-queries

PUT    /search-queries/{id}

DELETE /search-queries/{id}

POST   /search-queries/{id}/run

GET    /search-queries/{id}/history

GET    /search-runs/{id}/results
```

Точные REST contracts уточняются на этапе API design.

---

# 60. GitHub Client

Взаимодействие с GitHub изолируется:

```text
github.client
```

Application/domain modules не должны самостоятельно выполнять GitHub HTTP calls.

Пример:

```text
TrafficCollector
       ↓
GitHubTrafficClient
       ↓
GitHub REST API
```

---

# 61. GitHub Rate Limits

Client должен обрабатывать:

- rate-limit headers;
- Search API limits;
- rate-limit reset;
- retryable errors;
- non-retryable errors.

Rate Limit не должен приводить к бесконечному retry.

Search workload изолирован отдельным worker pool именно в том числе из-за отличающейся rate-limit модели.

---

# 62. Error Handling

Единый REST error format:

```json
{
  "code": "GITHUB_RATE_LIMIT_EXCEEDED",
  "message": "GitHub API rate limit exceeded",
  "timestamp": "...",
  "traceId": "..."
}
```

Категории:

```text
VALIDATION_ERROR
NOT_FOUND
GITHUB_AUTH_ERROR
GITHUB_RATE_LIMIT_EXCEEDED
GITHUB_API_ERROR
DATABASE_ERROR
INTERNAL_ERROR
```

---

# 63. Observability

Spring Boot Actuator.

Health:

```text
/actuator/health
```

Metrics:

```text
github.api.requests
github.api.errors

collection.jobs.ready
collection.jobs.running
collection.jobs.failed

collection.duration

search.jobs.ready
search.jobs.failed
search.duration

repositories.tracked
```

Prometheus endpoint предоставляется как optional integration.

Prometheus/Grafana не входят в стандартный Docker Compose v0.1/v0.2.

---

# 64. Logging

Используется structured logging.

В logs должны присутствовать при наличии:

- trace ID;
- repository ID;
- collection run ID;
- collection job ID;
- search run ID;
- GitHub request duration;
- error category.

GitHub Token никогда не логируется.

---

# 65. Testing

## Unit

- JUnit 5;
- AssertJ.

## Persistence

- PostgreSQL Testcontainers.

H2 вместо PostgreSQL не используется.

## GitHub API

Используется **WireMock**.

Минимальные сценарии:

- 200;
- pagination;
- empty result;
- 401;
- 403;
- rate limit;
- 404;
- 422;
- 5xx;
- timeout;
- malformed payload.

## Queue / Worker

Обязательно проверить:

- duplicate Planner run;
- partial collection;
- retry;
- lease expiration;
- worker crash;
- restart recovery;
- concurrent workers;
- one-active-job-per-repository;
- Search/Collection lock conflict.

---

# 66. Liquibase

Все schema changes выполняются через Liquibase.

Пример:

```text
001-initial-schema
002-traffic
003-collection-queue
004-search-tracking
005-competitors
006-growth-events
```

Runtime `CREATE TABLE` не используется.

---

# 67. Non-Functional Requirements

Система не проектируется как high-load.

Целевой масштаб одной self-hosted installation:

```text
Repositories       <= 100
Search Queries     <= 100+
Search Top-N       50 default
History            several years
```

Обычные dashboard requests:

```text
p95 < 500 ms
```

при типичном объёме данных.

---

# 68. Reliability Requirements

- Planner idempotent.
- Collectors idempotent.
- Search Planner idempotent.
- Queue persistent.
- Application restart не теряет jobs.
- Worker crash не теряет job.
- Повторная обработка не создаёт duplicate data.
- Partial collection поддерживается.
- Ошибка одного collector не блокирует остальные.
- Ошибка одного Search Query не блокирует другие queries.

---

# 69. UX Requirement

Основной onboarding:

```text
git clone
    ↓
set GITHUB_TOKEN
    ↓
docker compose up -d
    ↓
localhost:8080
    ↓
select repositories
    ↓
Collect
    ↓
Dashboard
```

Цель — минимальная стоимость входа для open-source пользователя.

---

# 70. Acceptance Criteria — v0.1

v0.1 считается готовой, если:

1. Система запускается `docker compose up -d`.
2. Backend и frontend находятся в одном application image.
3. Web UI доступен на одном HTTP port.
4. PostgreSQL запускается автоматически.
5. GitHub Token принимается из environment/secrets.
6. Получается список repositories.
7. Поддерживаются personal public/private и organization repositories.
8. Пользователь выбирает tracked repositories.
9. Planner работает каждые 10 минут внутри configured strict window.
10. Повторный Planner не создаёт duplicate jobs.
11. Collection Queue хранится в PostgreSQL.
12. Workers корректно claim jobs.
13. Работает lease/recovery.
14. Для repository одновременно работает максимум один GitHub job.
15. Partial collection поддерживается.
16. Retry работает только для retryable errors.
17. Traffic history сохраняется.
18. Historical values сохраняются только при наличии реальных historical GitHub data.
19. Point-in-time snapshots задним числом не создаются.
20. Stars/Forks history сохраняется.
21. Referrers сохраняются.
22. Popular Paths сохраняются.
23. Portfolio Dashboard работает.
24. Repository Details работает.
25. Collection Status отображается.
26. Restart containers не приводит к потере history/queue.
27. Schema управляется Liquibase.
28. GitHub integration покрыта WireMock.
29. PostgreSQL integration покрыта Testcontainers.
30. Queue/recovery scenarios покрыты integration tests.

---

# 71. Acceptance Criteria — v0.2

v0.2 считается готовой, если:

1. Пользователь создаёт Search Query.
2. Query хранится целиком.
3. Один repository может иметь несколько queries.
4. Каждый Search Query создаёт независимый Search Run.
5. Search имеет отдельную persistent queue.
6. Search имеет отдельный worker pool.
7. Search и Collection используют общий repository-level concurrency control.
8. Один Search Query не блокирует остальные.
9. Search Planner idempotent.
10. Search работает внутри strict planning window.
11. Top-50 сохраняется по умолчанию.
12. Определяется позиция tracked repository.
13. Отсутствие в Top-N корректно хранится и отображается.
14. Rank history сохраняется.
15. Пропущенные дни не интерполируются как реальные observations.
16. Отображается current rank.
17. Отображается 7d change.
18. Отображается 30d change.
19. Отображается best rank.
20. Есть Search Rank chart.
21. Есть Search Results page.
22. Search Results содержат stars/forks snapshot.
23. Данные позволяют построить automatic competitor discovery в v0.3.

---

# 72. Целевая архитектура v0.1–v0.2

```text
                       GitHub API
                           ▲
                           │
              ┌────────────┴────────────┐
              │                         │
       Collection Workers          Search Workers
              ▲                         ▲
              │                         │
       collection_job               search_run
              ▲                         ▲
              │                         │
       Collection Planner          Search Planner
              ▲                         ▲
              └────────────┬────────────┘
                           │
                     Daily Planner
                   strict 10:00–18:00
                    every 10 minutes

              PostgreSQL Persistent Queues
                           │
                           │
                 Repository-level Lock
                    max 1 active/repo
```

На уровне приложения:

```text
GitHub
   ↓
Data Foundation
   ↓
Search Visibility
   ↓
Competitor Intelligence
   ↓
Growth Events
   ↓
Impact Analysis
   ↓
Growth Insights
```

Именно эта последовательность является основной архитектурной и продуктовой стратегией развития **Repo Growth Monitor**.