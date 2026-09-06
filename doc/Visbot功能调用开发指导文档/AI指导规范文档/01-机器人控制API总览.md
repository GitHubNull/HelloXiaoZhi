# 01-机器人控制 API 总览

> 本文档面向 AI 编程代理：描述 Visbot（优必选 Rosa 平台）机器人的**物理实体功能**调用体系。
> 所有 API 来自 `rosa.jar`（UBTech 官方 SDK，取自设备 `/system/framework/rosa.jar`，已反编译到 `tmp/rosa_decompiled/`）。

## 核心结论（先读这里）

Visbot 机器人的所有物理能力（走路、转头、挥手、表情、灯光）由设备上的 **Master 系统服务**统一管理。
第三方应用通过 **rosa.jar SDK** 跨进程（Binder IPC）调用这些能力：

```java
// 1. 初始化 SDK（自动完成与 Master 服务的 Binder 连接）
Robot.initialize(context.getApplicationContext());

// 2. 按服务名获取控制器
ServoController servo = Robot.globalContext().getSystemService("servo");          // 舵机 → 扭头/挥手
LocomotionController loco = Robot.globalContext().getSystemService("locomotion"); // 运动 → 走路/转向
MotionController motion = Robot.globalContext().getSystemService("motion");       // 动作 → 预置动作
EmotionController emotion = Robot.globalContext().getSystemService("emotion");    // 表情
LightController light = Robot.globalContext().getSystemService("light");          // 灯光

// 3. 异步调用（返回 ProgressivePromise）
servo.rotate(new RotationOption.Builder("head").setAngle(30).setSpeed(50).build());
```

### 硬性前置条件

| 条件 | 说明 |
|------|------|
| **系统签名** | APK 必须声明 `android:sharedUserId="android.uid.system"` 并用设备平台密钥签名（`tmp/ubt_repo/keystore/` 内有 `platform.pk8`/`platform.x509.pem`/`platform.jks`） |
| **ROBOT 权限** | Manifest 声明 `<uses-permission android:name="com.ubtrobot.permission.ROBOT"/>` |
| **rosa.jar** | 编译时依赖（`tmp/ubt_repo/visbot-sdk/libs/rosa.jar`），运行时由 `/system/framework/rosa.jar` 提供，无需打包 |
| **Master 服务** | 设备原厂系统服务（`com.ubtrobot.systemservice`），不随原厂应用卸载而消失 |

## 能力地图（10 个控制器）

`Robot.globalContext().getSystemService(name)` 的 `name` 参数与控制器对应关系：

| name | 接口 | 物理能力 | 文档 |
|------|------|----------|------|
| `"servo"` | `ServoController` | 单关节舵机（头、手臂等）转角度 → **扭头、挥手** | 02 |
| `"locomotion"` | `LocomotionController` | 底盘轮式运动 → **前进/后退/转向** | 03 |
| `"motion"` | `MotionController` | 预置动作表演（按 URI 播放动作文件）→ **挥手、鞠躬、舞蹈** | 04 |
| `"emotion"` | `EmotionController` | 表情动画（屏幕表情 URI） | 05 |
| `"light"` | `LightController` | LED 灯颜色与特效 | 06 |
| `"sensor"` | `SensorController` | 传感器读写（触摸/红外等） | 06 |
| `"power"` | `PowerController` | 电源管理（休眠/唤醒/关机） | 07 |
| `"recharging"` | `RechargingController` | 自动回充（回桩/离桩） | 07 |
| `"part"` | `PartController` | 部件热插拔状态（无直接动作） | 07 |
| `"upgrade"` | `UpgradeController` | OTA 升级（与物理动作无关） | — |

## SDK 架构与 IPC 链路

```
┌────────────────────────────────────────────────────────────────┐
│ 第三方应用（需系统签名 + sharedUserId=android.uid.system）      │
│   ServoController / LocomotionController / ... (rosa.jar)       │
└──────────────────────────┬─────────────────────────────────────┘
                           │ Binder IPC（通过 Robot.initialize 建立的连接）
┌──────────────────────────▼─────────────────────────────────────┐
│ Master Service（com.ubtrobot.systemservice，设备原厂系统服务）   │
│   ├─ Servo Service        (com.ubtrobot.servo)                  │
│   ├─ Locomotion Service   (com.ubtrobot.locomotion)             │
│   ├─ Motion/Emotion/Light/Sensor/Power/Recharging Service       │
│   └─ CompetitionManager（多应用并发竞争硬件时的会话仲裁）        │
└──────────────────────────┬─────────────────────────────────────┘
                           │ HAL
┌──────────────────────────▼─────────────────────────────────────┐
│ 硬件：舵机、电机、LED、传感器                                    │
└─────────────────────────────────────────────────────────────────┘
```

关键点：
- 所有调用均为**异步**，返回 `Promise` / `ProgressivePromise`（`com.ubtrobot.async`），带进度回调
- 底层协议是 `ParcelableCallAdapter` 通过 Binder 向 Master 服务发送 `ParcelRequest`（详见 08）
- 不走 rosa.jar 的裸 IPC 方案已实现于 `visbot-sdk` 模块的 `MasterConnection`（ContentProvider `com.ubtrobot.provider.master` 获取 Binder + `transact("WRIT")`）

## 初始化与获取服务的标准姿势

```java
// Application.onCreate 中初始化一次
Robot.initialize(getApplicationContext());

// 任意位置获取控制器（getSystemService 是同步方法，轻量）
ServoController servo = Robot.globalContext().getSystemService("servo");
```

也可以像 `MotorControllerClient`（`tmp/ubt_repo/visbot-sdk/.../MotorControllerClient.java`）那样包装成业务类。

## 通用异步模式（ProgressivePromise）

```java
ProgressivePromise<Void, ServoException, RotationProgress> promise =
    servo.rotate(new RotationOption.Builder("head").setAngle(45).setSpeed(50).build());

promise.done(new DoneCallback<Void>() {
    @Override public void onDone(Void aVoid) { /* 完成 */ }
}).fail(new FailCallback<ServoException>() {
    @Override public void onFail(ServoException e) { /* 失败：e.getCode() */ }
}).progress(new ProgressivePromise.ProgressCallback<RotationProgress>() {
    @Override public void onProgress(RotationProgress progress) { /* 进度 */ }
});
```

## 动作/表情/灯光的 URI 从哪来

`MotionController.performAction(PerformingOption)` 与 `EmotionController.express(ExpressingOption)` 均以 `android.net.Uri` 指定动作/表情。URI 资源存放在设备的**动作资源库**（由系统服务管理，不在第三方应用 APK 内）。获取方式：
1. 通过 `Robot.globalContext().getSystemService("motion")` 的动作服务查询可用动作列表（查询接口见 04 文档）
2. 动作 URI 一般为 `ubtrobot://action/<action_id>` 或服务返回的绝对路径

## 已存在的现成工程

`tmp/ubt_repo/` 是一个**已验证可用的完整客户端工程**（VisbotClient）：
- `visbot-sdk/`：SDK 封装模块（`ServoControllerClient`、`MotorControllerClient`、`MasterConnection`、`MasterServiceProxy`）
- `app/`：320×240 横屏示例应用（`MainActivity` 演示舵机旋转+前进后退）
- `keystore/`：平台签名密钥
- `libs/rosa.jar`：官方 SDK

新项目直接依赖该工程的 `visbot-sdk` 模块即可。

## 常见坑

1. **没签名就调用** → `SecurityException`。必须系统签名且 `sharedUserId="android.uid.system"`
2. **getSystemService 返回 null** → 设备上 Master 服务未运行（原厂系统服务被禁）或包名不在系统服务白名单
3. **Promise 不回调** → 竞争会话未释放：`RotationOption` 等长时间操作应配合 SessionAllocator（见 08）
4. **参数超范围** → 舵机角度超 [-90,90]、速度超 [0,100] 会被服务拒绝；`LocomotionOption.setDuration(0)` 表示持续直到下一条命令
