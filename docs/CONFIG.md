# 配置说明

所有配置都在 `config/rules.json` 文件中。

## 配置结构

```json
{
  "version": "1.0.0",
  "general": { ... },
  "scenes": { ... },
  "gpu_monitor": { ... },
  "frame_monitor": { ... },
  "risk_mitigation": { ... },
  "logging": { ... }
}
```

## general - 通用设置

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `run_mode` | string | `"log_only"` | 运行模式：`full_protect` / `log_only` |
| `permission_mode` | string | `"none"` | 权限模式：`root` / `shizuku` / `none` |
| `sample_interval_ms` | int | `500` | 性能采样间隔（毫秒） |
| `heartbeat_interval_ms` | int | `3000` | 心跳间隔（毫秒） |
| `protection_cooldown_ms` | int | `5000` | 保护冷却时间（毫秒） |

## scenes - 场景配置

每个场景包含以下字段：

```json
{
  "scene_id": {
    "name": "场景名称",
    "description": "场景描述",
    "enabled": true,
    "detection": { ... },
    "protection": { ... },
    "risk_mitigation": { ... }
  }
}
```

### detection - 检测参数

| 字段 | 类型 | 说明 |
|------|------|------|
| `entity_rate_threshold` | int | 实体数量估算阈值，超过此值可能触发保护 |
| `spike_window_ms` | int | 尖峰检测窗口（毫秒） |
| `consecutive_spikes` | int | 需要连续多少个尖峰才触发 |

### protection - 保护参数

| 字段 | 类型 | 说明 |
|------|------|------|
| `cpu_throttle` | float | CPU 降频比例，0-1，1=最大性能 |
| `gpu_throttle` | float | GPU 降频比例，0-1，1=最大性能 |
| `frame_limit` | int | 帧率限制（FPS） |
| `duration_ms` | int | 保护持续时间（毫秒） |
| `fade_out_ms` | int | 渐变恢复时间（毫秒） |

### risk_mitigation - 风险缓释

| 字段 | 类型 | 说明 |
|------|------|------|
| `max_daily_triggers` | int | 每日最大触发次数 |
| `cooldown_after_max` | int | 达到上限后的冷却时间（毫秒） |
| `gradual_escalation` | bool | 是否渐进式升级保护强度 |

## gpu_monitor - GPU 监控

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `spike_threshold_percent` | int | `85` | GPU 尖峰阈值百分比 |
| `baseline_window_ms` | int | `10000` | 基线计算窗口（毫秒） |
| `spike_cooldown_ms` | int | `2000` | 尖峰冷却时间（毫秒） |

## frame_monitor - 帧率监控

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `drop_threshold_percent` | int | `40` | 帧率下降阈值百分比 |
| `min_fps` | int | `15` | 最低帧率警戒线 |
| `drop_window_ms` | int | `1000` | 帧率下降检测窗口 |

## risk_mitigation - 全局风险缓释

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `account_safety_level` | string | `"normal"` | 账号安全等级 |
| `max_protections_per_hour` | int | `20` | 每小时最大保护次数 |
| `min_interval_between_protections_ms` | int | `2000` | 两次保护最小间隔 |
| `warning_threshold_count` | int | `10` | 警告阈值次数 |
| `auto_slow_down_after_warnings` | bool | `true` | 达到警告后自动降频 |

## logging - 日志配置

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `level` | string | `"INFO"` | 日志级别：DEBUG / INFO / WARN / ERROR |
| `max_log_size_mb` | int | `50` | 最大日志文件大小（MB） |
| `log_to_file` | bool | `true` | 是否记录到文件 |
| `include_metrics` | bool | `true` | 是否包含性能指标 |

## 内置场景

### 1. 枪限挑战 (gun_limit_challenge)

- 大量敌人同时生成与销毁
- 高 GPU 压力场景
- 保护强度：中高

### 2. 千星奇域 (thousand_star_domain)

- 大量特效和实体
- 高渲染压力
- 保护强度：中等

### 3. 新国家副本 (new_nation_dungeon)

- 大规模战斗场景
- 极高压力场景
- 保护强度：高

## 配置调优指南

### 闪退还是太频繁？
1. 降低 `gpu_throttle` 值（更强的降频）
2. 减少 `consecutive_spikes`（更早触发）
3. 增加 `duration_ms`（保护更持久）

### 保护太频繁、影响体验？
1. 提高 `entity_rate_threshold`（提高触发门槛）
2. 增加 `consecutive_spikes`（需要更多尖峰才触发）
3. 提高 `gpu_throttle` 值（更弱的降频）
4. 降低 `max_daily_triggers`（限制每日次数）

### 新游戏版本更新？
1. 观察日志中的性能数据
2. 调整场景的检测阈值
3. 添加新场景配置

## 示例：添加自定义场景

```json
"my_custom_scene": {
  "name": "我的自定义场景",
  "description": "这是一个自定义场景",
  "enabled": true,
  "detection": {
    "entity_rate_threshold": 30,
    "spike_window_ms": 2000,
    "consecutive_spikes": 2
  },
  "protection": {
    "cpu_throttle": 0.65,
    "gpu_throttle": 0.55,
    "frame_limit": 30,
    "duration_ms": 5000,
    "fade_out_ms": 2000
  },
  "risk_mitigation": {
    "max_daily_triggers": 100,
    "cooldown_after_max": 600000,
    "gradual_escalation": true
  }
}
```
