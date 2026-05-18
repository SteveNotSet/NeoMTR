# Rail Model Placement Direction System

本文档详细描述 `BakedRail.java` 中轨道模型放置时各方向属性如何协同工作。

## 概述：方向相关属性

| 属性 | 定义位置 | 含义 |
|------|---------|------|
| **Canonical 方向** | `BakedRail` 构造函数 | 两个节点中 `posStart.asLong() <= posEnd.asLong()` 为 canonical 起点。是数据存储的统一坐标方向。 |
| **isSecondaryDir** | `RailExtraSupplier` (Rail 上的属性) | 用户设置的"轨道整体朝向"开关，XOR 到所有 attachment 的 reversed 上。 |
| **offsetFromStart** | `RailModelRepeater` | 决定 FIXED_INTERVAL 模式下放置从 canonical 起点还是终点开始。也决定多模型循环的计数方向。 |
| **attachment.reversed** | `RepeaterAttachment` | 单个附件的反转标志，决定该附件的模型是否旋转 180°。 |

## 模型放置的完整流程

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         BakedRail 构造函数                                │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. 确定 Canonical 方向                                                    │
│    isCanonical = posStart.asLong() <= posEnd.asLong()                   │
│    canonStart = isCanonical ? posStart : posEnd                         │
│    canonEnd   = isCanonical ? posEnd : posStart                         │
│                                                                         │
│    ※ Canonical 方向与用户从哪端连接铁轨无关，只取决于坐标大小关系。          │
│    ※ tCanon 是沿 canonical 方向的距离参数 (0 = canonStart, L = canonEnd)   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 2. 读取 isSecondaryDir                                                   │
│    来自 RailExtraSupplier，是用户在 GUI 中翻转的整体方向开关。              │
│    每次 brush 模板 "匹配" 当前轨道时自动翻转。                              │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. 计算放置位置 (computePositions)                                        │
│                                                                         │
│    位置参数全部在 canonical 坐标系中计算 (tCanon)：                         │
│                                                                         │
│    ┌──────────────────────────────────────────────────────────────────┐ │
│    │ STRETCH_INTERVAL:                                                │ │
│    │   N = round(L / interval) + 1                                   │ │
│    │   t[k] = k * (L / (N-1))     k = 0..N-1                        │ │
│    │   在 canonical 方向上均匀分布                                      │ │
│    ├──────────────────────────────────────────────────────────────────┤ │
│    │ FIXED_INTERVAL:                                                  │ │
│    │   if offsetFromStart:                                            │ │
│    │     t = offset, offset+I, offset+2I, ...  (沿 canonical 方向)    │ │
│    │   else:                                                          │ │
│    │     t = L-offset, L-offset-I, ...  (从 canonical 终点往回)        │ │
│    │     然后 reverse 得到从小到大排列                                   │ │
│    ├──────────────────────────────────────────────────────────────────┤ │
│    │ MANUAL:                                                          │ │
│    │   直接使用 manualPositions (已是 canonical 坐标)                   │ │
│    └──────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│    结果: IndexedPosition(tCanon, originalIndex)                          │
│    originalIndex = 该位置在列表中的序号                                    │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 4. 为每个位置解析 Attachments                                             │
│    resolveAttachments(repeater, positionIndex):                         │
│      如果 instanceOverrides 有该 positionIndex 的覆盖 → 使用覆盖         │
│      否则 → 使用 repeater.attachments                                   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 5. 对每个 Attachment 计算模型索引 (computeModelIndex)                      │
│                                                                         │
│    effectiveIndex = offsetFromStart                                     │
│                     ? positionIndex                                     │
│                     : (totalPositions - 1 - positionIndex)              │
│                                                                         │
│    fmi = attachment.firstModelIndex % modelCount                        │
│    modelIndex = (fmi + effectiveIndex) % modelCount                     │
│                                                                         │
│    ※ offsetFromStart 控制模型循环是从 canonical 起点还是终点开始计数。       │
│    ※ firstModelIndex 控制第一个模型是哪个。                                │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 6. 计算最终旋转方向 (effectiveReversed)                                   │
│                                                                         │
│    effectiveReversed = isSecondaryDir XOR attachment.reversed           │
│                                                                         │
│    isSecondaryDir = false, reversed = false  →  effectiveReversed=false │
│    isSecondaryDir = false, reversed = true   →  effectiveReversed=true  │
│    isSecondaryDir = true,  reversed = false  →  effectiveReversed=true  │
│    isSecondaryDir = true,  reversed = true   →  effectiveReversed=false │
│                                                                         │
│    ※ isSecondaryDir 翻转所有附件的朝向。                                   │
│    ※ attachment.reversed 再单独翻转该附件。两者叠加 (XOR)。                │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 7. 计算变换矩阵 (computeMatrix)                                          │
│                                                                         │
│  7a. 坐标转换: tCanon → tLocal                                           │
│      tLocal = isCanonical ? tCanon : (railLength - tCanon)              │
│      ※ tLocal 是 Rail.getPosition() 使用的参数                            │
│      ※ Rail.getPosition(0) 总是返回 posStart 坐标                        │
│                                                                         │
│  7b. 获取放置点世界坐标:                                                   │
│      pos = rail.getPosition(tLocal)                                     │
│      xc, yc, zc = pos + (0, yOffset, 0)                                │
│                                                                         │
│  7c. 计算切线方向:                                                        │
│      在 tLocal 附近取两个采样点 tA, tB (间距 = chordHalfSpan):             │
│      pA = rail.getPosition(tA),  pB = rail.getPosition(tB)             │
│      切线向量 = pB - pA (沿 tLocal 增大方向)                               │
│                                                                         │
│      forward 向量:                                                       │
│        xf = xc + (pB.x - pA.x)                                         │
│        yf = yc + (tiltToGradient ? (pB.y - pA.y) : 0)                  │
│        zf = zc + (pB.z - pA.z)                                         │
│                                                                         │
│  7d. 确定偏移坐标系方向:                                                   │
│      needFlipForOffset = (isCanonical != offsetFromStart)               │
│                                                                         │
│      ※ 偏移坐标系的 +Z 始终指向 offsetFromStart 方向:                       │
│        offsetFromStart=true  → +Z 指向 canonical 方向                    │
│        offsetFromStart=false → +Z 指向 reverse canonical 方向            │
│      ※ STRETCH_INTERVAL/MANUAL 也遵循此规则                               │
│                                                                         │
│  7e. 生成偏移帧 LookAt 矩阵 + 应用偏移:                                    │
│      mat = getLookAtMat(xc, yc, zc, xf, yf, zf, needFlipForOffset)     │
│      mat.translate(offsetX, offsetY, offsetZ)                           │
│                                                                         │
│      ※ 偏移在 offsetFromStart 方向对齐的局部坐标系中:                       │
│        offsetX = 右手侧 (面向 offsetFromStart 方向时的右方)                 │
│        offsetY = 上方 (含坡度倾斜如果 tiltToGradient)                      │
│        offsetZ = 前方 (= offsetFromStart 方向)                            │
│                                                                         │
│  7f. 应用额外旋转到最终模型朝向:                                            │
│      needAdditionalPi = (effectiveReversed != needFlipForOffset)        │
│      if (needAdditionalPi) mat.rotateY(π)                              │
│                                                                         │
│      ※ 利用恒等式: RotX(p)*RotY(π) = RotY(π)*RotX(-p)                    │
│        使得 R_offset * T_offset * RotY(π) = R_final * T_offset'         │
│        最终模型朝向与之前完全一致                                           │
│                                                                         │
│      最终模型朝向仍由 effectiveReversed 决定:                               │
│      ※ effectiveReversed=false: 模型 +Z 朝向 canonical 正方向             │
│      ※ effectiveReversed=true:  模型 +Z 朝向 canonical 反方向             │
└─────────────────────────────────────────────────────────────────────────┘
```

## 坐标系统关系图

```
  世界坐标系 (Minecraft)
  ┌─────────────────────────────────┐
  │  Y (上)                          │
  │  │                               │
  │  │    Z (南→北)                   │
  │  │   /                           │
  │  │  /                            │
  │  │ /                             │
  │  └───────── X (西→东)            │
  └─────────────────────────────────┘

  轨道的 Canonical 方向:
  ──────────────────────────────────────────────────────►
  canonStart                                      canonEnd
  (posStart.asLong()                    (posStart.asLong()
   <= posEnd.asLong()                    > posEnd.asLong()
   时为 posStart)                        时为 posStart)

  tCanon: 0                                          L (railLength)
  tLocal: depends on isCanonical:
    isCanonical=true:   tLocal = tCanon     (same direction)
    isCanonical=false:  tLocal = L - tCanon (reversed)
```

## 偏移坐标系 (由 offsetFromStart 决定)

```
  当 offsetFromStart = true (偏移 +Z = canonical 方向):

      偏移 +Y (上)
         │
         │    偏移 +Z (朝向 canonEnd)
         │   /
         │  /
         │ /
         └──────── 偏移 +X (canonical 方向右手侧)


  当 offsetFromStart = false (偏移 +Z = reverse canonical 方向):

      偏移 +Y (上)
         │
         │    偏移 +Z (朝向 canonStart)
         │   /
         │  /
         │ /
         └──────── 偏移 +X (reverse canonical 方向右手侧)
```

注意：模型最终朝向由 effectiveReversed 决定，可能与偏移坐标系方向相同或相反 180°。
偏移位置不受模型朝向影响。

## 各属性互动的流程总结图

```
 ┌────────────────────┐
 │  Rail 原始连接方向    │    用户从哪端连向哪端
 │  (posStart, posEnd) │
 └─────────┬──────────┘
           │
           ▼
 ┌────────────────────┐
 │   确定 canonical    │    isCanonical = posStart.asLong() <= posEnd.asLong()
 │   (确定性的)         │    canonical 方向只取决于两个 BlockPos 的数值大小
 └─────────┬──────────┘
           │
           ├─────────────────────────────────────────────────┐
           ▼                                                 ▼
 ┌────────────────────┐                           ┌────────────────────────┐
 │  位置计算           │                           │  切线方向               │
 │  (在 canonical     │                           │  pB - pA 沿 tLocal 增  │
 │   坐标下)           │                           │  大方向                 │
 │                    │                           │  (即 isCanonical=true   │
 │  offsetFromStart   │                           │   时为 canonical 正方向;│
 │  影响起算端         │                           │   isCanonical=false     │
 └─────────┬──────────┘                           │   时为 canonical 反方向)│
           │                                      └────────────┬───────────┘
           │                                                   │
           ▼                                                   ▼
 ┌────────────────────┐                           ┌────────────────────────┐
 │  模型序号计算        │                           │  基础朝向 (yaw/pitch)   │
 │  effectiveIndex =  │                           │  由切线方向确定          │
 │    offsetFromStart  │                           │  模型默认面向 canonical  │
 │    ? posIdx        │                           │  正方向 (isCanonical=   │
 │    : total-1-posIdx│                           │  true) 或反方向         │
 │                    │                           │  (isCanonical=false)   │
 │  modelIdx =        │                           └────────────┬───────────┘
 │   (fmi+effIdx)     │                                        │
 │   % modelCount     │                                        ▼
 └────────────────────┘                           ┌────────────────────────┐
                                                  │  effectiveReversed     │
                                                  │  = isSecondaryDir      │
                                                  │    XOR                 │
                                                  │    attachment.reversed │
                                                  │                        │
                                                  │  true → 额外旋转 180°  │
                                                  │         (沿 Y 轴)      │
                                                  └────────────┬───────────┘
                                                               │
                                                               ▼
                                                  ┌────────────────────────┐
                                                  │  应用 Attachment 偏移   │
                                                  │  (在旋转后的局部坐标系) │
                                                  │  offsetX/Y/Z           │
                                                  └────────────────────────┘
```

## 各属性的实际效果

### Canonical 方向 (内部使用)
- **作用**: 统一数据存储。无论用户从哪端连接轨道，两个 BlockPos 按 `asLong()` 数值排序后确定唯一方向。
- **对模型的影响**: 通过 `tCanon → tLocal` 的转换影响切线的计算方向。当 `isCanonical=false` 时 `tLocal = L - tCanon`，使得 `getPosition(tLocal)` 从实际 posStart 端开始。

### isSecondaryDir
- **作用**: 用户级的整体翻转开关。让用户可以翻转一条轨道上所有模型的朝向。
- **对模型的影响**: 直接 XOR 到 `effectiveReversed`，影响模型的 yaw 旋转 (±180°)。
- **不影响**: 放置位置、偏移量方向、模型序号。

### offsetFromStart
- **作用**: 决定 FIXED_INTERVAL 模式下模型是从 canonical 起点还是终点开始放置。
- **对位置的影响**: 控制第一个模型放在哪一端。
- **对模型序号的影响**: 控制多模型循环的计数方向。`offsetFromStart=true` 时 index 0 在 canonical 起点端。

### attachment.reversed
- **作用**: 单个附件的反转标志。
- **对模型的影响**: XOR 到 `effectiveReversed`，使该附件的模型额外旋转 180°。
- **典型用途**: 在同一 Repeater 中同时放置面向两个方向的模型 (如信号机正面和背面)。

### attachment.offsetX / offsetY / offsetZ
- **作用**: 在 offsetFromStart 方向对齐的坐标系中平移模型位置。
- **坐标系** (始终以 offsetFromStart 方向为准):
  - X = 面向 offsetFromStart 方向时的右方 (垂直于切线，水平)
  - Y = 上方 (垂直于切线，含坡度倾斜如果 tiltToGradient=true)
  - Z = 前方 (= offsetFromStart 方向，与模型最终朝向无关)
- **注意**: 偏移坐标系由 offsetFromStart 唯一确定，不受 isSecondaryDir 和 attachment.reversed 影响。即使模型最终朝向翻转 180°，偏移位置不变。

## getLookAtMat 详解

```java
Matrix4f matrix4f = Translation(posX, posY, posZ);
float yaw = atan2(dx, dz);      // XZ 平面的朝向角
float pitch = atan2(dy, hDist);  // 仰俯角

matrix4f.rotateY((reverse ? π : 0) + yaw);
matrix4f.rotateX(reverse ? pitch : -pitch);
```

- `yaw` 使模型 +Z 轴对齐切线在 XZ 平面的投影方向
- `pitch` 使模型沿坡度倾斜
- `reverse=true` 时额外加 π 的 Y 轴旋转，且 pitch 符号取反，实现完整的 180° 翻转

最终模型的 +Z 轴指向：
- `reverse=false`: 沿 rail.getPosition(tLocal) 增大的方向
- `reverse=true`: 沿 rail.getPosition(tLocal) 减小的方向
