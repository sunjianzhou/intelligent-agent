# K8s 命令积累

## 2026-06-17
- `kubectl get pods -n <namespace>`: 查看指定命名空间的 Pod 列表
- `kubectl describe pod <name>`: 查看 Pod 详细事件（排障必用）
- `kubectl logs <pod> -c <container>`: 查看指定容器日志
- `kubectl apply -f <file.yaml>`: 声明式应用配置（支持增量更新）
- `kubectl rollout status deployment/<name>`: 查看滚动更新进度
