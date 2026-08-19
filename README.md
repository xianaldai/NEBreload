# 网络包优化 | Not Enough Bandwidth Legacy(NEBL)
这是 [NEB](https://github.com/USS-Shenzhou/NotEnoughBandwidth) 的反向移植分支，出现的任何问题，请勿提交 issue 到上游的原作者仓库，谢谢理解！

This is a backport branch of [NEB](https://github.com/USS-Shenzhou/NotEnoughBandwidth). For any problems you encounter, please do not submit issues to the upstream author's repository. Thank you for your understanding!

![icon](src/main/resources/icon-enbl.png)

## 简介 | Introduction

NEBL通过多种方式来尽可能地节省Minecraft游玩过程中产生的流量，并对模组和玩家保持透明。

在[TeaCon 甲辰](https://teacon.cn)的土球数据集中，相比未压缩的原始数据，NEBL理论上可以将服务器的出站流量减少到原来的7.6%。作为对比，原版默认压缩机制的出站流量是原始数据大小的39%。

NEBL uses various methods to save as much network traffic as possible during Minecraft gameplay, while remaining transparent to both mods and players.

In the ZZZZ Dataset from [TeaCon Jiachen](https://teacon.cn), compared to raw uncompressed data, NEBL can theoretically reduce the server's outbound traffic to 7.6% of its original size. For comparison, the outbound traffic of Vanilla's default compression mechanism is 39% of the original data size.

<img width="1908" height="1908" alt="output" src="https://github.com/user-attachments/assets/5e015031-f6e8-4280-a280-17da859a8615" />

在原版环境下的测试中，服务器出站流量被减少到原来的18%。理论上，随着安装的模组数量的增加，网络传输的内容会更多更复杂，压缩效果会更好。

In tests conducted in a Vanilla environment, the server outbound traffic was reduced to 18% of its original size. Theoretically, as the number of installed mods increases, the content transmitted over the network becomes larger and more complex, leading to better compression performance.

在游戏中按下Alt+F8来简单地查看流量情况。

Press Alt+F8 in-game to easily view the network traffic status.

<img width="2559" height="1383" alt="image" src="https://github.com/user-attachments/assets/216dea71-dbc7-40f2-8117-d20fcf74cd11" />

## 主要功能 | Main Features

### 紧凑的包头 | Compact Packet Header

优化`CustomPacketPayload`编码及对应解码，以紧凑的索引替代包头的`Identifier`(Packet Type)，使模组网络包包头消耗减少为固定3-4字节，而不是网络包类型对应的字符串长度。

Optimize `CustomPacketPayload` encoding and decoding by replacing the packet header `Identifier` (Packet Type) with a compact index. This reduces the mod network packet header overhead to a fixed 3-4 bytes, instead of the length of the string corresponding to the network packet type.

索引结构如下：

The index structure is as follows:

> [!NOTE]
> ### Fixed 8 bits header
> ```
> ┌------------- 1 byte (8 bits) ---------------┐
> │               function flags                │
> ├---┬---┬-------------------------------------┤
> │ i │ t │      reserved (6 bits)              │
> └---┴---┴-------------------------------------┘
> ```
> - i = indexed (1 bit)
> - t = tight_indexed (1 bit, only valid if i=1)
> - reserved = 6 bits (for future use)
>
> ### Indexed packet type
> - If i=0 (not indexed):
> ```
> ┌---------------- N bytes ----------------
> │ Identifier (packet type) in UTF-8
> └-----------------------------------------
> ```
> - If i=1 and t=0 (indexed, NOT tight):
> ```
> ┌-------- 1 byte ---------┬-------- 1 byte --------┬-------- 1 byte --------┐
> ┌------------- 12 bits ---------------┬-------------- 12 bits --------------┐
> │    namespace-id (capacity 4096)     │       path-id (capacity 4096)       │
> └-------------------------------------┴-------------------------------------┘
> ```
> - If i=1 and t=1 (indexed, tight):
> ```
> ┌--------- 1 byte ----------┬--------- 1 byte ---------┐
> ┌--------- 8 bits ----------┬--------- 8 bits ---------┐
> │namespace-id (capacity 256)│  path-id (capacity 256)  │
> └---------------------------┴--------------------------┘
> ```
> Then packet data.

网络包`namespace`及对应的每个`path`少于256时为3字节，大于256时为4字节。即最多支持4096个模组，每个模组4096条通道。

It occupies 3 bytes when the network packet namespace and its corresponding path are fewer than 256, and 4 bytes when greater than 256. This supports up to 4096 mods, with 4096 channels per mod.

### 聚合与压缩 | Aggregation and Compress

优化原版经常出现大量小体积网络包的情况，在`Connection`层面拦截发送，每隔20ms组装为一个大网络包，并进行压缩后发送。

Optimize the situation where vanilla often produces a large number of small network packets. Intercept transmission at the `Connection` level, assemble them into one large network packet every 20ms, and send it after compression.

> [!NOTE]
> ```
> ┌---┬----┬----┬----┬----┬----┬----...
> │ S │ p0 │ s0 │ d0 │ p1 │ s1 │ d1 ...
> └---┴----┴----┴----┴----┴----┴----...
>     └--packet 1---┘└--packet 2---┘
>     └----------compressed----------┘
> ```
> - S = varint, size of compressed buf
> - p = prefix (medium/int/utf-8)， type of this subpacket
> - s = varint, size of this subpacket
> - d = bytes, data of this subpacket

### 延迟区块缓存 | Delayed Chunk Cache

在原版，当玩家移动时，服务端会指示客户端立即忘记身后的区块；如果又回到原来的位置，就需要发送区块的全量信息。通过延后这个“忘记”，可以节省在机器上跳来跳去时产生的区块发送流量。

In Vanilla, when a player moves, the server instructs the client to immediately forget the chunks behind them; if the player returns to the original position, the full chunk data must be sent again. By delaying this "forgetting", the chunk transmission traffic generated when poking around can be saved.

### 字典引用去重 | Dictionary Reference Deduplication

对于重复出现的网络包，NEBL会在收发双方维护一个包字典：完全相同的包会被替换为仅数字节的精确引用；内容相近的包则通过模板引用只传输变化的部分，从而避免重复发送相同的数据。

For packets that appear repeatedly, NEBL maintains a packet dictionary on both ends: byte-identical packets are replaced with a tiny exact reference, while similar packets are transmitted via a template reference that carries only the changed bytes, avoiding re-sending the same data.

此外，服务端会对下发的区块包计算指纹并记录，客户端则缓存完整的区块包。当玩家再次进入相同区块时，服务端只需发送一个区块引用，客户端直接应用缓存的区块，从而大幅减少重复区块产生的流量。

Additionally, the server fingerprints and records sent chunk packets while the client caches the full chunk packets. When the player re-enters the same chunk, the server only sends a chunk reference and the client applies its cached chunk, greatly reducing the traffic caused by repeated chunks.

## 配置 | Config

在`config/NotEnoughBandwidthLegacyConfig.json`修改配置文件。

Modify the configuration file at `config/NotEnoughBandwidthLegacyConfig.json`.

### configVersion

> [!WARNING]
> **该值由 NEBL 自动维护，请勿手动修改。**
>
> THIS VALUE IS AUTO-MAINTAINED BY NEBL. DO NOT EDIT.

配置文件的版本号，用于 NEBL 维护配置文件的默认值。

The version number of the config file, used by NEBL to maintain the defaults of the config file.

### compatibleMode

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

是否要开启兼容模式。如果为true，下方的`blackList`会被启用。

Whether to enable compatibility mode. If set to `true`, the `blackList` below will be used.

### blackList

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

兼容模式黑名单。在黑名单中的包会被NEBL跳过。默认自带一系列和velocity相关的包，你也可以按需增加新的包。

The blacklist for compatibility mode. Packets listed here will be skipped by NEBL. By default, it includes a list of Velocity-related packets, but you can add new packets as needed.

> [!WARNING]
> 为确保包的顺序性，黑名单中的包会打断正在进行的聚合。如果黑名单中有许多的包，或者对应包发送过于频繁，则聚合-压缩的效率会降低。
> 
> To ensure packet ordering, packets in the blacklist will interrupt the ongoing aggregation. If there are many packets in the blacklist, or if the corresponding packets are sent too frequently, the efficiency of aggregation-compression will decrease.

### debugLog

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

是否输出调试日志。开启后，NEBL会在日志中输出聚合压缩、包字典、区块引用等模块的统计信息，便于排查问题。默认为`false`。

Whether to output debug logs. When enabled, NEBL logs statistics from the aggregation, packet dictionary, and chunk reference modules to help troubleshoot issues. Default is `false`.

### contextLevel

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

在进行压缩时的上下文窗口长度。可选范围为21\~25的整数，分别代表2\~32MB。默认为23，即8MB。

上下文窗口长度越长，则压缩效果越好，越节省流量；但也会消耗更多的内存。

The context window size used for compression. Valid values are integers from 21 to 25, representing 2MB to 32MB respectively. The default is 23 (8MB).

A larger context window results in better compression and bandwidth savings, but consumes more memory.

> [!TIP]
> 对于100名玩家的服务器，设置为25会产生约额外3200MB的内存占用。
> 
> For a server with 100 players, a setting of 25 will result in approximately 3200MB of additional memory usage.

### dccEnabled, dccSizeLimit, dccDistance, dccTimeout

> [!NOTE]
> **这些选项仅在服务端生效。**
> 
> ONLY WORK ON SERVER.

延迟区块缓存（DCC）允许的最大缓存区块数量、缓存区块距离、缓存过期时间。较大的值可能会占用更多内存或区块空洞，较小的值可能会更频繁地触发更新。

The maximum number of cached chunks, cached chunk distance, and cache timeout allowed by the Delayed Chunk Cache (DCC). Larger values may consume more memory or cause chunk holes, while smaller values may trigger updates more frequently.

> [!WARNING]
> 如果你正在专用服务器中与 Voxy/DH 等类似模组一起使用，请将服务器配置的`dccEnabled`设置为`false`，避免出现黑色LoD。
> 
> If you are using this on a dedicated server alongside similar mods such as Voxy/DH, set `dccEnabled` to `false` in the server config to avoid black LoD.

### maxPacketSize

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

聚合后单个网络帧的最大大小，支持`B`/`KB`/`MB`后缀（如`"4MB"`），有效范围2MB\~64MB，默认`"4MB"`。原版帧解码器的上限较小，聚合压缩后的大包可能超过该上限导致断开；此选项会同步提高帧编解码和custom payload解码的上限。

The maximum size of a single network frame after aggregation, supporting `B`/`KB`/`MB` suffixes (e.g. `"4MB"`), valid range 2MB~64MB, default `"4MB"`. The vanilla frame decoder limit is small, and large compressed batches may exceed it and cause disconnects; this option raises the frame encode/decode and custom payload decode limits accordingly.

### loginTimeoutSeconds

> [!NOTE]
> **此选项仅在服务端生效。**
>
> ONLY WORK ON SERVER.

登录阶段的超时时间（秒），默认`180`。原版限制为30秒（600 tick），在大型模组包中可能不够用；此选项会将其延长为对应的tick数（秒数×20）。

The login phase timeout in seconds, default `180`. The vanilla limit is 30 seconds (600 ticks), which may be insufficient on large modpacks; this option extends it to the corresponding number of ticks (seconds × 20).

### connectionTimeoutSeconds

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

连接保持超时时间（秒），默认`180`。客户端用它替换原版的读超时handler，服务端用它延长keepalive超时（原版15秒），以适应大型模组包下较慢的加载与传输。

The connection keep-alive timeout in seconds, default `180`. On the client side it replaces the vanilla read timeout handler, and on the server side it lengthens the keep-alive timeout (vanilla 15 seconds), accommodating slower loading and transfer on large modpacks.

### packetDictionaryEnabled

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

是否启用包字典（映射）层。开启后，收发双方会为重复出现的包建立精确引用和模板引用，只传输差异部分，进一步节省流量。默认为`true`。

Whether to enable the packet dictionary (mapping) layer. When enabled, both sides create exact and template references for repeated packets and only transfer the differences, saving more bandwidth. Default is `true`.

### packetDictionaryMaxPacketBytes

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

参与包字典匹配的最大子包大小（字节），默认`4096`。超过该大小的包直接以字面量发送，不参与字典匹配。

The maximum sub-packet size (in bytes) eligible for packet dictionary matching. Default is `4096`. Packets larger than this are sent as literals without dictionary matching.

### packetDictionaryMaxEntries

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

包字典最多保存的条目数（精确引用+模板引用），默认`8192`。超出后按最近最少使用（LRU）淘汰。

The maximum number of entries stored in the packet dictionary (exact references + template references). Default is `8192`. Entries are evicted by least-recently-used (LRU) order when exceeded.

### packetDictionaryMaxPayloadBytes

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

包字典存储的负载总字节数上限，默认`1048576`（1MB），用于限制字典占用的内存。

The maximum total payload bytes stored by the packet dictionary. Default is `1048576` (1MB), limiting the memory used by the dictionary.

### packetDictionaryMaxDiffRuns

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

创建模板匹配时允许的最大差异段数量，默认`8`。差异段过多时该包不会被用于创建模板。

The maximum number of diff runs allowed when creating a template match. Default is `8`. Packets with too many diff runs will not be used to create a template.

### packetDictionaryMaxChangedBytes

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

创建模板匹配时允许的最大差异总字节数，默认`128`。超过该值时该包不会被用于创建模板。

The maximum total changed bytes allowed for template matching. Default is `128`. Above this, the packet will not be used to create a template.

### chunkReferenceEnabled

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

是否启用区块引用层。开启后，服务端为下发的区块包计算指纹并记录，客户端缓存完整的区块包；当玩家再次进入相同区块时只发送引用，客户端直接应用缓存的区块，大幅减少重复区块的流量。默认为`true`。

Whether to enable the chunk reference layer. When enabled, the server fingerprints and records sent chunk packets while the client caches the full chunk packets; when the player re-enters the same chunk, only a reference is sent and the client applies its cached chunk, greatly reducing repeated chunk traffic. Default is `true`.

### chunkReferenceMaxServerEntries

> [!NOTE]
> **此选项仅在服务端生效。**
>
> ONLY WORK ON SERVER.

服务端区块引用表最多保存的区块数量，默认`8192`。按最近使用（LRU）顺序淘汰。

The maximum number of chunk entries kept in the server-side chunk reference table. Default is `8192`. Entries are evicted by least-recently-used (LRU) order.

### chunkReferenceMaxClientCache

> [!NOTE]
> **此选项仅在客户端生效。**
>
> ONLY WORK ON CLIENT.

客户端最多缓存的完整区块包数量，默认`8192`。按最近使用（LRU）顺序淘汰。

The maximum number of full chunk packets cached on the client. Default is `8192`. Entries are evicted by least-recently-used (LRU) order.

### chunkReferenceMinBytes

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

参与区块引用的最小区块包大小（字节），默认`4096`。小于该值的区块包直接发送，不做引用。

The minimum chunk packet size (in bytes) eligible for chunk referencing. Default is `4096`. Smaller chunk packets are sent directly without referencing.

### streaming

> [!NOTE]
> **此选项在客户端和服务端独立生效。**
>
> WORK INDEPENDENTLY ON CLIENT AND SERVER.

是否启用Zstd流式传输，默认`false`。开启后，同一连接的多次传输会共用一个连续的Zstd帧，压缩上下文的历史窗口在批次间复用，每批次不再写入独立的END帧，可以进一步节省流量；但由于各批次之间相互依赖，一旦网络出现丢包或数据损坏，后续所有批次都会级联解码失败，且无法通过清除缓存恢复，只能重新连接。请仅在网络足够稳定时开启。

Whether to enable Zstd streaming transfer. Default is `false`. When enabled, all transfers on a connection share one continuous Zstd frame: the compression context's history window is reused across batches, and each batch no longer writes an independent END frame, which can save more bandwidth. However, batches become interdependent: if any batch is lost or corrupted on the network, all subsequent batches fail to decode (cascading corruption), and the stream cannot be resynchronized by clearing caches — only by reconnecting. Enable it only when your network is stable.

### playersDoNotUseContext

> [!NOTE]
> **此选项仅在服务端生效。**
>
> ONLY WORK ON SERVER.

指定一个特殊的UUID名单，在这个名单中的玩家不启用Zstd上下文复用。适用于Replay等依赖网络包重放的mod。

Assign a special UUID list, server will not reuse ZSTD context for these players. Designed for mods such as Replay, which relies on packet re-play.

## 版权和许可 | Copyrights and Licenses

Copyright (C) 2025 USS_Shenzhou

本模组是自由软件，你可以再分发之和/或依照由自由软件基金会发布的 GNU 通用公共许可证修改之，无论是版本 3 许可证，还是（按你的决定）任何以后版都可以。

发布这个模组是希望它能有用，但是并无保障；甚至连可销售和符合某个特定的目的都不保证。请参看 GNU 通用公共许可证，了解详情。

Copyright (C) 2025 USS_Shenzhou

This mod is free software; you can redistribute it and/or modify them under the terms of the GNU General Public License as published by the Free Software Foundation; either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

### 额外许可 | Additional Permissions

a）当你作为游戏玩家，加载本程序于Minecraft并游玩时，本许可证自动地授予你一切为正常加载本程序于Minecraft并游玩所必要的、不在GPL-3.0许可证内容中、或是GPL-3.0许可证所不允许的权利。如果GPL-3.0许可证内容与Minecraft EULA或其他Mojang/微软条款产生冲突，以后者为准。

a) As a game player, when you load and play this program in Minecraft, this license automatically grants you all rights necessary, which are not covered in the GPL-3.0 license, or are prohibited by the GPL-3.0 license, for the normal loading and playing of this program in Minecraft. In case of conflicts between the GPL-3.0 license and the Minecraft EULA or other Mojang/Microsoft terms, the latter shall prevail.
