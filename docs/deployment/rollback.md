# 回滚操作

```bash
docker compose -f infra/docker-compose.yml -f docker-compose.app.yml down

# 将 .env 中的 IMAGE_TAG 或镜像引用恢复到上一版本
docker compose -f infra/docker-compose.yml -f docker-compose.app.yml up -d
docker compose -f infra/docker-compose.yml -f docker-compose.app.yml ps
```

数据库迁移不可盲目回滚。回滚应用前确认上一版本能够读取当前 schema；如迁移不可兼容，使用经过验证的数据库备份恢复，并暂停所有 Worker，避免任务重复执行。
