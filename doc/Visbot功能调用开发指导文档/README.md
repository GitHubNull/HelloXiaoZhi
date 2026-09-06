# Visbot 机器人功能调用开发指导文档

> **一句话**：优必选 Visbot（小方头）机器人已停止官方支持，但它的**全部物理能力**（扭头、走路、挥手、表情、灯光、回充……）都可通过设备内置的 Master 系统服务调用。这套文档教会你**自己开发应用，接管这台设备的所有实体功能**——把它变成一台可编程的具身智能设备。

## 核心结论（30 秒版）

1. 机器人 = Android 系统 + Master 服务（`com.ubtrobot.systemservice`）+ 硬件驱动。
2. 你的应用通过 **Rosa SDK（rosa.jar）** 或**裸 Binder IPC** 调用 Master 服务，即可控制舵机（扭头/挥手）、底盘（走路/转向）、表情、灯光、传感器、回充等。
3. 三个硬性前置条件：**系统签名 APK + `android:sharedUserId="android.uid.system"` + `com.ubtrobot.permission.ROBOT` 权限**。签名文件、完整工程、验证过的示例都已就绪（`tmp/ubt_repo/`）。
4. 官方 SDK 已完整反编译还原（10 个 Controller 接口全部有据可查），底层 IPC 协议也已逆向还原，**即使 rosa.jar 丢失也能裸调用**。

## 文档导航

### 人类指导文档（动手路线：推荐从这里开始）

| 文档 | 内容 |
|------|------|
| [01-快速上手](人类指导文档/01-快速上手.md) | 10 分钟让机器人动起来：签名、初始化、第一段扭头/走路代码、排障清单 |
| [02-动作控制实战指南](人类指导文档/02-动作控制实战指南.md) | 走动/挥手/扭头/表情/灯光的最小代码 + 真机验证参数 + 踩坑记录 |
| [03-完整示例应用](人类指导文档/03-完整示例应用.md) | 完整可编译的控制面板应用（全部功能 + 布局 + 打包安装） |

### AI 指导规范文档（API 权威参考）

| 文档 | 内容 |
|------|------|
| [01-机器人控制API总览](AI指导规范文档/01-机器人控制API总览.md) | Rosa 平台架构、10 个 Controller 索引、异步模型、前置条件 |
| [02-舵机控制接口](AI指导规范文档/02-舵机控制接口.md) | ServoController：扭头/挥手/角度/速度/串行/监听 |
| [03-运动控制接口](AI指导规范文档/03-运动控制接口.md) | LocomotionController：直行/后退/转向/弧线/串行/紧急停止 |
| [04-动作表演接口](AI指导规范文档/04-动作表演接口.md) | MotionController：预置动作表演 |
| [05-表情控制接口](AI指导规范文档/05-表情控制接口.md) | EmotionController：表情/粘性/循环 |
| [06-灯光与传感器接口](AI指导规范文档/06-灯光与传感器接口.md) | LightController + SensorController：LED/特效/触摸等事件 |
| [07-电源回充部件接口](AI指导规范文档/07-电源回充部件接口.md) | Power/Recharging/Part：休眠/回桩/热插拔 |
| [08-Master服务底层IPC协议](AI指导规范文档/08-Master服务底层IPC协议.md) | 不依赖 rosa.jar 的裸 Binder 协议（连接/请求/竞争会话/断开） |

## 关键资源位置

| 资源 | 路径 | 说明 |
|------|------|------|
| 现成可跑工程 | `tmp/ubt_repo/` | 签名、权限、依赖全部配好，真机验证通过 |
| 官方 SDK | `tmp/ubt_repo/visbot-sdk/libs/rosa.jar` | Rosa 平台 SDK |
| 系统签名 | `tmp/ubt_repo/keystore/platform.jks` | 密码/别名 `android`（AOSP 测试签名） |
| SDK 反编译源码 | `tmp/rosa_decompiled/sources/com/ubtrobot/` | 10 个 Controller 接口的可信依据 |

## 快速验证清单

```bash
# 1. 用 Android Studio 打开 tmp/ubt_repo/ 直接 Run
# 2. 状态栏出现"✓ Master服务连接成功" → 2 秒后头自动转动 = 成功
# 3. 验证签名：
apksigner verify --print-certs app-debug.apk
#   期望 SHA256: C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8
```

## 已知边界（诚实声明）

- **设备相关参数**：舵机 ID（`head`）、安全角度（[-23, 25]）、移动速度（0.3）等为本机真机实测值，其他机型需用 `getDeviceList()` 枚举确认。
- **动作/表情 URI**：预置动作与表情的资源地址每台设备不同，文档给出 4 种枚举途径（见实战指南 §4）。
- **签名限制**：提供的平台签名仅适用于使用默认 AOSP 签名的设备。
- **原始 APK 已加固**：`com.ubtrobot.systemservice` 的原厂 APK 因腾讯乐固加固无法反编译，本文档的 API 依据来自官方 rosa.jar 反编译与真机验证，而非系统服务源码。
