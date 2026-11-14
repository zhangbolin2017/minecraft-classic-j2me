# AGENTS.md — Minecraft Classic 0.0.11a → Sony Ericsson S500i（J2ME）移植版

## 这是什么
**Minecraft Classic 0.0.11a**（2009 年 5 月 16 日版本）的 J2ME（MIDP 2.0 / CLDC 1.1）移植版。原版是**桌面端 LWJGL/OpenGL** 的 Java SE 程序；本项目将其重写，面向 Sony Ericsson **JP-7** 平台手机（S500i），渲染采用 **Mascot Capsule Micro3D V3** 3D API（`com.mascotcapsule.micro3d.v3`）。

本项目最初是 rd-132211（RubyDung）的移植版，之后升级到了 Classic 0.0.11a：加入了真正的方块类型（岩石/草/泥土/石砖/木头/灌木）、带洞穴的分形地形生成、光照/阴影柱，以及随机刻更新（草蔓延、灌木枯萎）。

## 文档约定（2026-10-18 起）
- **游戏设定的关键信息**（操作、方块、生物、生存规则、世界设定）→ 记录在 `README.md`。
- **开发台账**（迭代记录、已知问题、待办）→ 记录在 `game_track.md`，新条目加在「迭代记录」最上方。
- 本文件（AGENTS.md）只保留移植规则、构建说明与代码地图。

## 事实来源（Source of truth）
- 桌面版 0.0.11a 经 CFR 反编译得到的类是游戏逻辑的参考（`com.mojang.minecraft.*`）。逻辑在 CLDC 允许的范围内尽可能忠实地移植；包名保留为 `com.mojang.rubydung.*` 以保持延续性。
- API 相关事实来自 Mascot Capsule v3 SDK 的 javadoc。关键调用：
  `renderPrimitives(Texture, int x, int y, FigureLayout, Effect3D, int command,
  int numPrimitives, int[] vtx, int[] nrm, int[] texCoord, int[] colors)`。

## 硬性规则
- **`stubs/`（Mascot Capsule 桩类）绝对不能打包进 JAR。** 手机固件原生提供这些类；把空桩类打进包里会破坏 3D 渲染。构建时会把 stubs 编译到单独的目录，仅作为编译期 classpath 使用。
- **纹理必须是正方形、且边长为 2 的幂。** Mascot Capsule Micro3D V3 在正方形 UV 空间中采样纹理，因此非正方形图集（Classic 原版为 256x128）在真机上会渲染出扭曲/旋转的结果。所以 `res/terrain.png` 和 `res/terrain.bmp` 都补边到 **256x256**：图集内容锚定在上半部分，下半部分留透明。`WorldRenderer` 中的 UV 坐标是绝对像素（tile 索引 -> u/v），因此补边不会偏移任何 tile。
- 目标为 **Java 1.3 字节码**（JDK 8 `-source/-target 1.3`），随后用 ProGuard `-microedition` 做 **preverify**（补充 CLDC 所需的 StackMap 属性）。
- CLDC **没有** `ArrayList`/泛型，**没有** `Math.random()`，**没有** 文件 I/O。请使用 `Vector`、`java.util.Random` 和 RMS `RecordStore`。CLDC 1.1 **确实有** `Math.sin/cos/sqrt` 和 `float`。

## 构建
`./build.sh`（需要位于 `/opt/jdk8u402-b06` 或 `$JAVA8_HOME` 的 JDK8，以及 `proguard`）。
输出：`dist/mc-c0.0.11a.jar` + `dist/mc-c0.0.11a.jad`。

## 代码地图
- `RubyDungMIDlet` - MIDlet 入口；把自身传给 GameCanvas 以便访问 Display（多人模式文字输入用）。
- `GameCanvas` - MIDP Canvas、游戏主循环、键盘输入；游戏内菜单（继续/保存/读取/新建/帮助/多人游戏）、**MC 风格 9 格快捷栏 + 物品栏**（贴图图标取 terrain.png/bone.png/bone_meal.png，`drawSlot`/`drawItemIcon`；伪物品 `ITEM_BONE=200`/`ITEM_BONE_MEAL=201` 与方块共用计数；选中 bone 按 `#` 弹 Yes/No 合成框，1 骨 → 3 骨粉，`craftBoneMeal()`）、方块破坏/放置与物品计数、生存模式 HUD（爱心 + 氧气泡、死亡/重生遮罩）、粒子/生物/箭/掉落物/网络玩家 tick、史莱姆生成（键 9）、虚空平台传送（空格）、多人游戏加入流程。
- `level/ILevel` + `level/InfiniteLevel` - 世界接口与区块化无限世界：16 格区块、LRU ≤96、跟随玩家的流式生成/卸载、方块增量记录、400-tick 随机刻节流。单人新世界默认用它。
- `level/Level` - Classic 0.0.11a 有限世界：`getTile/setTile/isLit/isSolidTile`、光照柱、随机刻 tick、RMS 存/读档。第二个构造函数接受预先加载的方块数据（网络地图）。地形由 `LevelGen` 生成。
- `level/LevelGen` + `level/NoiseMap` - 分形地形 + 挖洞（逐字移植）；含树木/花草种植与**逐岛泛洪撒绳**（每岛 ≥2 根、同柱可垂挂 2~4 格）。
- `level/InfiniteLevel` 区块生成同样撒绳：每个含陆地的 16×16 区块确定性放 ≥2 根（必须用种子哈希随机，重生成卸载区块需完全一致）。
- `level/tile/Tile` + `GrassTile`/`DirtTile`/`Bush` - 方块集合、各面纹理、tick 行为。注册表为 `Tile.tiles[id]`。
- `level/WorldRenderer` - Mascot Capsule 渲染器；将暴露面输出为带纹理的四边形、植物输出为交叉双面片、平色立方体网络玩家、公告板式破坏粒子，以及 3D 线框方块高亮（`renderHit`）。
- `RayCast` - DDA 体素拾取（替代 GL 的选择缓冲区）。
- `Player` - 生存模式玩家：移动/碰撞/固定步长计时，以及 Alpha 风格的生命值（20 HP）、摔落伤害、溺水（氧气值）、岩浆灼烧、掉入虚空致死、死亡与自动重生。**注意 `player.y` 是视点高度（bb.y0+1.62）**：与玩家做距离/拾取判定时要对身体中心（约 y-0.8）算，否则近地判定永远不成立。
- `phys/AABB` - 逐轴扫掠式碰撞裁剪（swept-axis clipping）。
- `Particle` - 破坏方块碎片：重力、落地碰撞、短暂生命周期；采样被破坏方块的纹理。
- `Slime` - 史莱姆生物：贴图取自 `/slime.bmp`；待机后跳向玩家（无 atan2，朝向存单位向量）、压扁死亡 + 果冻拉伸动画；死后掉落 1~2 个**粘液球**（`ITEM_SLIMEBALL=202`，公告板 `/slimeball.bmp`，实体复用 `Skeleton.Bone`，磁吸拾取）；夜间自然刷新（上限 3）。
- `RedMouse` - 红鼠哨兵生物：原地站立转向玩家、T 型弩俯仰瞄准、约 1.6s 一箭（当前伤害 0）、纯箱式模型、死亡掉落木板 ×2 与弩（`BowDrop`）。
- `Skeleton` - 骷髅近战生物：单方块头/身 + 单方块铁剑（细四肢与面部细节同 RedMouse 箱式风格）、走向玩家并近身挥剑一次（伤害 0，钩子保留）、白天不燃烧、遇台阶自动跳、6 HP、死亡时**全身散架**（7 个部件各自弹飞翻滚落地，`PARTS`/`partX/Y/Z/Yaw/Pitch`），掉落 2~3 根 `Skeleton.Bone`（公告板 `/bone.bmp`，磁吸拾取，仅 toast 计数）。
- `Firecracker` - 爆竹怪自爆生物：单方块绿色身体 + 头（苦力怕式黑脸）、**红色爆竹脚方块** ×2、可缩回的两节引线方块；近身点燃（引线缩头、身体闪烁）后自爆——爆炸由 `GameCanvas.doFirecrackerBlast` 执行：毁 2 格地形（基岩除外）、玩家 3 HP（0.15×20）、**其他生物免疫**；拳击拆除则**原地定格 1 秒**（`DEATH_FREEZE_TICKS`，期间绝不自爆、渲染为无引线哑炮）再散架，掉落 1~2 份火药（`ITEM_GUNPOWDER=203`，公告板复用 `Skeleton.Bone`/`renderItemSprites`）。夜间刷新，上限 2。
- `YouJi` - 酉鸡王召唤生物：圆石十字架 + 任意矿帽（14/15/16）召唤（`ritualAt` 已泛化为 barId+三帽参数，HaiZhu 传泥土+石/圆石，YouJi 传圆石+三矿）；粉头/粉身 + 红冠/黑眼细节 + 橙喙 + 左右橙翅 + 两根细条黄脚；静止神像、10 HP 每拳 1 伤；死亡为刚性后仰倒地（deathPitch 复用 RedMouse 机制）；无掉落。
- `HaiZhu` - 亥猪首召唤生物：不自然刷新；放泥土/原石/圆石时 `trySummonHaiZhu` 检测仪式：**石帽（原石或圆石）正下方 3~5 格连续泥土竖梁 + 梁上任意高度一对横臂**（`ritualAt` 以石帽为锚，T/十字/加号/高十字通吃；另有平铺加号 `isFlatDirtPlus`）→ 泥土石帽全部消失、原地召唤（`summonHaiZhuUnderCap`）。模型：悬浮红地毯方块 + 粉色猪头方块（鼻/眼细节）+ 嘴里绿色小方块；贴地滑行/浮沉；10 HP 且**每拳仅 1 伤**（`attackHaiZhu` 特判 hurt(1)），打死后**掉落**青色 **Haizhuball**（id 204 地面掉落物，唯一持有、耐久 100 显示在图标数字上；使用=准星单体秒杀四族怪物之一并 -1 耐久、无目标不耗，对亥猪首无效，归零消失，`useHaizhuball()`）。
- `DroppedBlock` - 掉落方块实体：重力、落地、靠近玩家被吸附、10 tick 拾取延迟，拾取计入物品数 `blockCounts`。
- `net/NetworkClient` - Classic / ClassiCube 多人客户端（原生协议，不含 CPE）。自带接收线程、登录握手、gzip 地图传输、实体增/移动/删除、聊天、设置方块；发送自身位置与方块编辑。封包大小逐字取自 ClassiCube 的 `src/Protocol.c`。
- `net/NetPlayer` - 远端玩家（位置插值 + yaw/pitch）。
- `net/Inflate` - 纯 Java 的 DEFLATE/gunzip 实现（CLDC 1.1 没有 `java.util.zip`），O(n)，32 KB 滑动窗口。

## 操作（数字键盘）
- 2/8 前进/后退，4/6 横向平移，1/3 转向（按住持续生效），7/9 仰视/俯视（按住），5 跳跃
- `*` 破坏方块，`#` 放置选中的方块，`0` 循环切换方块类型
- 左软键（或 FIRE）打开游戏内菜单；右软键打开物品栏选择器
- 方向键也可移动/转向；菜单内：2/8 导航，5/FIRE 确认

## 多人游戏
- 菜单 -> Multiplayer 通过 MIDP TextBox 依次输入主机、端口和用户名，然后经 `socket://host:port` 连接。
- 实现原版 Classic 协议（opcode 0-15）。不协商 CPE 扩展（CPE 魔数字节发 0 = 原生模式）。
- 坐标为定点数（1 方块 = 32 单位）。字符串为 64 字节空格补齐的 ASCII。
- 已针对模拟服务器完成端到端验证：登录、地图传输 + gunzip、实体生成、聊天、客户端位置/设置方块的上行发送。

## 迭代记录与待办
历史迭代明细、已知问题与后续计划统一记录在 `game_track.md`（2026-10-18 起生效），不再写入本文件。
