---
id: docker
title: Docker Setup
sidebar_position: 1
---

# Docker Setup

## Starting Everything

```bash
cd Microservices
docker compose up -d
```

To rebuild after code changes:

```bash
docker compose build delivery-service && docker compose up -d delivery-service
```

## Services

```mermaid
graph LR
    subgraph Routing["Routing (one-time setup)"]
        DL[osrm-download]
        PREP[osrm-prepare]
        RT[osrm]
        DL --> PREP --> RT
    end

    subgraph Core["Core Infrastructure"]
        RMQ[rabbitmq]
        DB1[(postgres-app\n5435)]
        DB2[(postgres-delivery\n5434)]
        DB3[(postgres-driver\n5437)]
        MINIO[minio\n9000/9001]
    end

    subgraph Services["Application Services"]
        IAM[app-backend\n8080]
        DS[delivery-service\n8082]
        DRV[driver-service\n8086]
        ERP[erp-adapter\n8088]
        GW[api-gateway\n80]
    end

    DB1 --> IAM
    DB2 --> DS
    DB3 --> DRV
    MINIO --> DS
    RT --> DS
    IAM --> GW
    DS --> GW
    DRV --> GW
```

## Health Checks

Every service has a healthcheck. Dependencies use `condition: service_healthy` so services start in the right order automatically.

| Service | Healthcheck |
|---|---|
| postgres-* | `pg_isready` |
| rabbitmq | `rabbitmq-diagnostics ping` |
| minio | `curl /minio/health/live` |
| app-backend | `nc -z localhost 8080` |
| delivery-service | `nc -z localhost 8082` |
| driver-service | `nc -z localhost 8086` |
| erp-adapter | `wget /actuator/health` |

## Volumes

| Volume | Contents |
|---|---|
| `app-pgdata` | IAM PostgreSQL data |
| `delivery-pgdata` | Delivery PostgreSQL data |
| `driver-pgdata` | Driver PostgreSQL data |
| `minio_data` | POD photos, PDFs, logos |
| `osrm-data` | Tunisia road network (osm.pbf + processed files) |
| `rabbitmq-data` | Message queue data |

:::warning OSRM First Run
The first `docker compose up` downloads and processes the Tunisia OSM file (~100MB). This can take 10–15 minutes. Subsequent starts are instant (data cached in volume).
:::
