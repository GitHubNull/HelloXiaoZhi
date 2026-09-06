# 08-Master 服务底层 IPC 协议（不依赖 rosa.jar 的裸调用）

> 用途：在**不依赖 rosa.jar** 的情况下，直接通过 Android Binder 调用设备 Master 服务。
> 依据：`tmp/ubt_repo/visbot-sdk/src/main/java/com/visbot/sdk/master/MasterConnection.java`（已验证可用的实现）。

## 协议总览

```
第三方应用
  │  1. ContentResolver.call(content://com.ubtrobot.provider.master, "connect", null, args)
  │     args: version="v1", package=<你的包名>, binder=<客户端 Binder>
  ▼
MasterProvider（com.ubtrobot.provider.master）
  │  2. 返回 Bundle: code=0(成功), binder=<服务端 Binder>
  ▼
Master 服务 Binder
  │  3. binder.transact(0x57524954 /*"WRIT"*/, data, reply, 0)
  │     data = [客户端Binder, ParcelMessage(ParcelRequest(context, config, path, param))]
  ▼
Master 服务按 path 路由到子系统服务（servo/locomotion/...）
```

## 1. 连接（获取服务端 Binder）

```java
private static final String AUTHORITY = "com.ubtrobot.provider.master";
private static final Uri PROVIDER_URI = Uri.parse("content://" + AUTHORITY);
private static final String VERSION = "v1";   // 从 Version.smali 发现：LIST={"v1"}, LATEST="v1"

IBinder clientBinder = new android.os.Binder();  // 客户端回调 Binder

Bundle args = new Bundle();
args.putString("version", VERSION);
args.putString("package", context.getPackageName());
args.putBinder("binder", clientBinder);

Bundle result = context.getContentResolver().call(PROVIDER_URI, "connect", null, args);

int code = result.getInt("code", -1);          // 0 = 成功
if (code == 0) {
    IBinder masterBinder = result.getBinder("binder");
}
// 失败时 result 含 "error_message"
```

注意：Provider 会校验调用方的**系统签名/白名单**，非签名应用连接被拒（这就是必须系统签名的底层原因）。

## 2. 发送请求（transact "WRIT"）

```java
private static final int TRANS_CODE_WRITE = 0x57524954;       // "WRIT"
private static final int TRANS_CODE_DISCONNECT = 0x4453434e;  // "DSCN"

Parcel data = Parcel.obtain();
Parcel reply = Parcel.obtain();

// (a) 第一个数据必须是客户端 Binder
data.writeStrongBinder(clientBinder);

// (b) 请求上下文：指明 requester（请求方包名）、responder（服务名/包名）、竞争会话
ParcelRequestContext context = new ParcelRequestContext.Builder(
        ParcelRequestContext.RESPONDER_TYPE_SERVICE)
    .setRequester("com.example.visbotclient")
    .setRequesterType(ParcelRequestContext.REQUESTER_TYPE_SERVICE)
    .setResponderPackage("com.ubtrobot.servo")     // 按服务名映射：servo→com.ubtrobot.servo
                                                   //              locomotor→com.ubtrobot.locomotion
    .setCompetingSession(sessionInfo)              // 可选
    .build();
// 注意：responder 字段（服务名）在 rosa.jar 中无公开 setter，
// 实现里用反射写入：responder = "servo"

// (c) 请求配置
ParcelRequestConfig config = new ParcelRequestConfig.Builder()
    .setHasCallback(false)
    .setStickily(false)
    .setTimeout(30000)
    .setCancelPrevious(false)
    .setPreviousRequestId(null)
    .build();

// (d) 参数：两种形态
//     JSON 参数（跨进程 ClassLoader 安全，推荐）：
JsonParam param = new JsonParam("{\"servoId\":\"head\"}");
//     或 Parcelable 参数：
// ParcelableParam param = ParcelableParam.create(rotationOptionList);

// (e) 组装请求与消息
ParcelRequest request = new ParcelRequest(context, config, "/servo/rotate", param);
ParcelMessage message = new ParcelMessage(request);
data.writeParcelable(message, 0);

// (f) 发送
boolean ok = masterBinder.transact(TRANS_CODE_WRITE, data, reply, 0);

// (g) 解析响应（ParcelResponse）
reply.readException();
ParcelResponse response = ParcelResponse.CREATOR.createFromParcel(reply);
response.getResultType();  // "success" 等
response.getCode();        // 0=成功
response.getMessage();
```

## 3. 服务名 → 包名映射

| 路径第一段（服务名） | responderPackage | 子系统 |
|----------------------|------------------|--------|
| `servo` | `com.ubtrobot.servo` | 舵机 |
| `locomotor` | `com.ubtrobot.locomotion` | 运动 |
| 其他 | `com.ubtrobot.<服务名>` | 通用 |

## 4. 已知服务路径清单

```
/servo/rotate            /servo/rotate-serially   /servo/angle
/servo/release           /servo/is-rotating       /servo/device
/servo/device-list
/locomotor/locomote      /locomotor/locomote-serially
/motion/perform          /motion/perform-serially /motion/posture
/emotion/express         /emotion/express-serially /emotion/dismiss
/light/turn-on           /light/turn-off          /light/change-color
/light/display-effect    /light/device-list
/sensor/enable           /sensor/disable          /sensor/control
/sensor/device-list
/power/sleep             /power/wake-up           /power/shutdown
/recharging/connect      /recharging/disconnect
```

## 5. 断开连接

```java
Parcel data = Parcel.obtain();
masterBinder.transact(TRANS_CODE_DISCONNECT, data, null, IBinder.FLAG_ONEWAY);
data.recycle();
```

## 6. 竞争会话（CompetitionSession）

多应用同时控制同一硬件时，Master 服务通过"竞争会话"仲裁。裸调用时需自行构建：

```java
// 会话分配由客户端发起：向 Master 服务申请对某资源的独占
CompetitionSessionInfo sessionInfo = new CompetitionSessionInfo.Builder()
    .setSessionId(sessionId)
    .addCompetingItem(item)   // 竞争项，如 "servo:head"
    .build();
```

SDK 侧封装：`SessionAllocator("servo", ServoConstants.COMPETING_ITEM_PREFIX_SERVO).allocate(ids)`。
若请求不携带会话，服务可能直接拒绝（返回非 0 code）。

## 7. 完整现成实现

`MasterConnection.java`（`tmp/ubt_repo/visbot-sdk/`）已实现以上全部协议，直接复用：

```java
MasterConnection connection = new MasterConnection(context);
connection.connect();                                    // 连接
connection.call("/servo/angle", "{\"servoId\":\"head\"}", sessionInfo);   // JSON 调用
connection.callWithParcelable("/servo/rotate", rotationOptionList, sessionInfo); // Parcelable 调用
connection.isConnected();
connection.disconnect();
```

## 注意事项

1. **响应异步**：多数动作类请求（rotate/locomote）实际执行是异步的，`transact` 返回的只是"已受理"；进度/结果通过连接时传入的客户端 Binder 回调
2. **ClassLoader 陷阱**：Parcelable 参数跨进程反序列化可能因 ClassLoader 不同失败（工程实测踩过坑），**查询类请求优先用 JsonParam**
3. **服务名反射**：`ParcelRequestContext` 的 `responder` 字段无公开 API，需反射设置
4. **超时**：默认 30s；长动作（回充）需注意
